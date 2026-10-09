package com.lanrex.sitecam.stamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.lanrex.sitecam.core.stamp.StampGeometry
import com.lanrex.sitecam.core.stamp.VideoBitrate
import com.lanrex.sitecam.core.stamp.VideoOverlayPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Burns the stamp into a copy of a video with Media3 Transformer.
 *
 * Resolution and frame rate stay as they are, the audio is copied unchanged,
 * the video codec is kept (H.264 or H.265) at about the original bitrate, and
 * HDR stays HDR when the phone can encode it (otherwise it is tone-mapped to
 * standard colour, and the user is told).
 */
@OptIn(UnstableApi::class)
class VideoStamper(private val context: Context, private val renderer: StampRenderer) : VideoStamping {

    /** One way of exporting; later ones are more compatible but less faithful. */
    private data class Attempt(val hdrMode: Int, val videoMime: String?, val bitrate: Int?)

    private class Overlay(val bitmap: Bitmap, val placement: VideoOverlayPlacement)

    override suspend fun stamp(
        request: VideoStamping.Request,
        output: File,
        onProgress: (Float) -> Unit,
    ): VideoStamping.Result {
        val info = request.info
        val width = info.displayWidth
        val height = info.displayHeight
        // Frames reach the effects upright (the decoder applies the rotation),
        // so the stamp is laid out exactly as on a photo of the same shape.
        val geometry = renderer.layout(width, height, request.lines, request.showMap)
        val overlay = renderOverlay(geometry, request.map, request.headingDegrees)

        val keepMime = info.videoMime?.takeIf { it == MimeTypes.VIDEO_H265 || it == MimeTypes.VIDEO_H264 }
        val bitrate = VideoBitrate.target(info.videoBitrate, request.sizeBytes, info.durationMillis)
        val attempts = buildList {
            add(Attempt(Composition.HDR_MODE_KEEP_HDR, keepMime, bitrate))
            if (info.isHdr) add(Attempt(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, keepMime, bitrate))
            // Last resort: the most widely supported encoder settings.
            val lastHdrMode = if (info.isHdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR
            add(Attempt(lastHdrMode, MimeTypes.VIDEO_H264, null))
        }

        // The overlay bitmap is left to the garbage collector: Media3 may still be
        // reading it on its own thread for a moment after an export is cancelled.
        var lastError: ExportException? = null
        for (attempt in attempts) {
            output.delete()
            try {
                val result = export(request, attempt, overlay, output, onProgress)
                val keptHdr = isHdr(result.colorInfo?.colorTransfer)
                val note = if (info.isHdr && !keptHdr) {
                    "This HDR video was saved in standard colour because this phone can't edit HDR video."
                } else {
                    null
                }
                return VideoStamping.Result(note)
            } catch (e: ExportException) {
                lastError = e
                // Missing or unreadable files won't improve with other settings.
                if (e.errorCode in 2000..2999) break
            }
        }
        output.delete()
        throw CannotStampException(describe(lastError), lastError)
    }

    /** Draws the stamp into a bitmap aligned to whole video pixels, so it stays sharp. */
    private fun renderOverlay(g: StampGeometry, map: MapTile?, headingDegrees: Float?): Overlay {
        val placement = VideoOverlayPlacement.of(g.bounds, g.imageWidth, g.imageHeight)
        val bitmap = Bitmap.createBitmap(placement.width, placement.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-placement.left.toFloat(), -placement.top.toFloat())
        renderer.draw(canvas, g, map, headingDegrees)
        return Overlay(bitmap, placement)
    }

    private suspend fun export(
        request: VideoStamping.Request,
        attempt: Attempt,
        overlay: Overlay,
        output: File,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main.immediate) {
        // The bitmap's top-left corner goes on the stamp's top-left pixel.
        val settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(-1f, 1f)
            .setBackgroundFrameAnchor(overlay.placement.anchorX, overlay.placement.anchorY)
            .build()
        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlay.bitmap, settings)
        val overlayEffect = OverlayEffect(listOf<TextureOverlay>(bitmapOverlay))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(request.source))
            .setEffects(Effects(emptyList(), listOf<Effect>(overlayEffect)))
            .build()
        val sequence = if (request.info.hasAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(edited))
        }
        val composition = Composition.Builder(sequence)
            .setHdrMode(attempt.hdrMode)
            .build()

        val encoderSettings = VideoEncoderSettings.Builder()
            .apply { attempt.bitrate?.let { setBitrate(it) } }
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(encoderSettings)
            .setEnableFallback(true)
            .build()

        suspendCancellableCoroutine<ExportResult> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .apply { attempt.videoMime?.let { setVideoMimeType(it) } }
                .setEncoderFactory(encoderFactory)
                .setLooper(Looper.getMainLooper())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            poll = Runnable {
                if (!cont.isActive) return@Runnable
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                handler.postDelayed(poll, PROGRESS_POLL_MS)
            }
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
            try {
                transformer.start(composition, output.absolutePath)
                handler.postDelayed(poll, PROGRESS_POLL_MS)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun isHdr(colorTransfer: Int?): Boolean =
        colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG

    private fun describe(e: ExportException?): String = when {
        e == null -> "This video could not be stamped."
        e.errorCode in 2000..2999 -> "SiteCam could not read this video. It may have been moved or deleted."
        e.errorCode in 3000..3999 -> "This phone can't decode this video's format, so it can't be stamped."
        e.errorCode in 4000..4999 -> "The phone's video encoder refused this video (${e.errorCodeName})."
        else -> "Video stamping failed (${e.errorCodeName})."
    }

    private companion object {
        const val PROGRESS_POLL_MS = 500L
    }
}
