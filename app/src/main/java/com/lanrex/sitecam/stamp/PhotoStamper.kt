package com.lanrex.sitecam.stamp

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lanrex.sitecam.core.jpeg.ChromaSubsampling
import com.lanrex.sitecam.core.jpeg.JpegEncoder
import com.lanrex.sitecam.core.jpeg.JpegStripWriter
import com.lanrex.sitecam.core.stamp.ExifOrientation
import com.lanrex.sitecam.core.stamp.StampGeometry
import com.lanrex.sitecam.core.stamp.StampLines
import com.lanrex.sitecam.media.PhotoInfo
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A photo that can't be stamped safely (too big for memory, unreadable, unsupported). */
class CannotStampException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Burns the stamp into a full-resolution JPEG copy of a photo.
 *
 * The photo is decoded and encoded in horizontal bands, so even a 200 MP photo
 * never has to fit in memory at once and is never resized. Output is JPEG
 * quality 100 with no filters. Pixels keep their stored orientation (the EXIF
 * orientation tag is copied), and the stamp is drawn rotated to match.
 */
class PhotoStamper(private val context: Context, private val renderer: StampRenderer) {

    data class Request(
        /** Readable URI of the original (with GPS intact where possible). */
        val source: Uri,
        val info: PhotoInfo,
        val lines: StampLines,
        val showMap: Boolean,
        val map: MapTile?,
        val headingDegrees: Float?,
    )

    data class Result(val geometry: StampGeometry, val subsampling: ChromaSubsampling)

    fun stamp(request: Request, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): Result {
        val sw = request.info.storedWidth
        val sh = request.info.storedHeight
        if (sw > 65535 || sh > 65535) throw CannotStampException("This photo is wider than the JPEG format allows.")
        val orientation = request.info.exifOrientation
        val (dw, dh) = ExifOrientation.displaySize(orientation, sw, sh)
        val geometry = renderer.layout(dw, dh, request.lines, request.showMap)
        val values = ExifOrientation.storedFromDisplay(orientation, sw, sh)
        val storedFromDisplay = Matrix().apply {
            setValues(floatArrayOf(values[0], values[1], values[2], values[3], values[4], values[5], 0f, 0f, 1f))
        }
        val stampRows = ExifOrientation.storedRows(orientation, sw, sh, geometry.bounds)

        // 4:4:4 keeps full colour detail; above 64 MP use 4:2:0 like the camera does,
        // otherwise a 200 MP quality-100 file would be several hundred MB.
        val subsampling = if (sw.toLong() * sh > BIG_PHOTO_PIXELS) ChromaSubsampling.YUV420 else ChromaSubsampling.YUV444
        val encoder = JpegEncoder(sw, sh, quality = 100, subsampling = subsampling)
        val writer = JpegStripWriter(encoder)

        val drawStamp: (Canvas) -> Unit = { canvas ->
            canvas.concat(storedFromDisplay)
            renderer.draw(canvas, geometry, request.map, request.headingDegrees)
        }

        try {
            openRowSource(request.source, sw, sh, writer.stripRows, stampRows, drawStamp).use { source ->
                BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { out ->
                    writer.write(out, emptyList(), source, isCancelled, onProgress)
                }
            }
        } catch (e: OutOfMemoryError) {
            output.delete()
            throw CannotStampException("Not enough memory to stamp this ${megapixels(sw, sh)} MP photo safely.", e)
        }
        return Result(geometry, subsampling)
    }

    private interface ClosableRowSource : JpegStripWriter.RowSource, Closeable

    private fun openRowSource(
        uri: Uri,
        width: Int,
        height: Int,
        stripRows: Int,
        stampRows: IntRange,
        drawStamp: (Canvas) -> Unit,
    ): ClosableRowSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Could not open the photo.")
        val decoder = try {
            if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(pfd)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
            }
        } catch (e: Exception) {
            null
        }
        if (decoder != null && decoder.width == width && decoder.height == height) {
            return RegionRowSource(decoder, pfd, width, height, stripRows, stampRows, drawStamp)
        }
        decoder?.recycle()
        pfd.close()
        return FullBitmapRowSource(uri, width, height, stampRows, drawStamp)
    }

    /** Decodes ~48 MB bands one after another and draws the stamp into the bands it covers. */
    private class RegionRowSource(
        private val decoder: BitmapRegionDecoder,
        private val pfd: ParcelFileDescriptor,
        private val width: Int,
        private val height: Int,
        stripRows: Int,
        private val stampRows: IntRange,
        private val drawStamp: (Canvas) -> Unit,
    ) : ClosableRowSource {
        private val bandRows: Int = run {
            val rows = (BAND_BYTES / (width.toLong() * 4)).toInt()
            ((rows / stripRows) * stripRows).coerceAtLeast(stripRows)
        }
        private var band: Bitmap? = null
        private var bandTop = 0
        private var bandBottom = 0

        override fun readRows(firstRow: Int, rowCount: Int, dest: IntArray) {
            if (band == null || firstRow < bandTop || firstRow + rowCount > bandBottom) loadBand(firstRow)
            band!!.getPixels(dest, 0, width, 0, firstRow - bandTop, width, rowCount)
        }

        private fun loadBand(top: Int) {
            val bottom = minOf(height, top + bandRows)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inMutable = true
                val previous = band
                if (previous != null && previous.isMutable) inBitmap = previous
            }
            var bitmap = decoder.decodeRegion(Rect(0, top, width, bottom), options)
                ?: throw CannotStampException("Part of this photo could not be decoded.")
            if (!bitmap.isMutable) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            band = bitmap
            bandTop = top
            bandBottom = bottom
            if (!stampRows.isEmpty() && top <= stampRows.last && bottom > stampRows.first) {
                val canvas = Canvas(bitmap)
                canvas.translate(0f, -top.toFloat())
                drawStamp(canvas)
            }
        }

        override fun close() {
            band?.recycle()
            decoder.recycle()
            pfd.close()
        }
    }

    /**
     * Fallback when the region decoder can't read the file (some HEIC files):
     * decode the whole photo, but only if that is safe for this phone's memory.
     */
    private inner class FullBitmapRowSource(
        uri: Uri,
        private val width: Int,
        height: Int,
        stampRows: IntRange,
        drawStamp: (Canvas) -> Unit,
    ) : ClosableRowSource {
        private val bitmap: Bitmap

        init {
            val bytes = width.toLong() * height * 4
            if (bytes > safeFullDecodeBytes()) {
                throw CannotStampException(
                    "This ${megapixels(width, height)} MP photo can't be stamped safely on this phone " +
                        "(not enough memory to open it in one piece).",
                )
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inMutable = true
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw CannotStampException("This photo format can't be read.")
            if (decoded.width != width || decoded.height != height) {
                decoded.recycle()
                throw CannotStampException("This photo decoded at an unexpected size, so it was not stamped.")
            }
            bitmap = if (decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true)
            if (!stampRows.isEmpty()) drawStamp(Canvas(bitmap))
        }

        override fun readRows(firstRow: Int, rowCount: Int, dest: IntArray) {
            bitmap.getPixels(dest, 0, width, 0, firstRow, width, rowCount)
        }

        override fun close() {
            bitmap.recycle()
        }
    }

    private fun safeFullDecodeBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return minOf(info.availMem / 3, 800L * 1024 * 1024)
    }

    companion object {
        const val BIG_PHOTO_PIXELS = 64_000_000L
        private const val BAND_BYTES = 48L * 1024 * 1024

        fun megapixels(w: Int, h: Int): Long = (w.toLong() * h + 500_000) / 1_000_000
    }
}
