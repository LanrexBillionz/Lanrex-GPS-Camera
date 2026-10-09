package com.lanrex.sitecam.core.stamp

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the stamp goes on a video frame (upright, display orientation).
 *
 * The stamp bitmap is aligned to whole pixels so every frame gets a sharp 1:1
 * copy of it. The anchor is the position of the bitmap's top-left corner in
 * Media3's frame coordinates (-1..1, x to the right, y up).
 */
data class VideoOverlayPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val anchorX: Float,
    val anchorY: Float,
) {
    companion object {
        fun of(bounds: Box, frameWidth: Int, frameHeight: Int): VideoOverlayPlacement {
            require(frameWidth > 0 && frameHeight > 0) { "Empty video frame" }
            val left = floor(bounds.left).toInt().coerceIn(0, frameWidth - 1)
            val top = floor(bounds.top).toInt().coerceIn(0, frameHeight - 1)
            val right = ceil(bounds.right).toInt().coerceIn(left + 1, frameWidth)
            val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, frameHeight)
            return VideoOverlayPlacement(
                left = left,
                top = top,
                width = right - left,
                height = bottom - top,
                anchorX = (2f * left / frameWidth - 1f).coerceIn(-1f, 1f),
                anchorY = (1f - 2f * top / frameHeight).coerceIn(-1f, 1f),
            )
        }
    }
}

object VideoBitrate {
    /** Camera audio is about 256 kbit/s. */
    const val AUDIO_ESTIMATE = 256_000L
    const val MIN = 1_000_000L
    const val MAX = 200_000_000L

    /**
     * Bitrate to ask the encoder for, so the stamped copy keeps about the
     * original quality: the video track's own bitrate when the file states it,
     * otherwise worked out from the file size and length. Null when unknown.
     */
    fun target(videoTrackBitrate: Int?, fileSizeBytes: Long, durationMillis: Long): Int? {
        if (videoTrackBitrate != null && videoTrackBitrate > 0) return videoTrackBitrate
        if (fileSizeBytes <= 0 || durationMillis <= 0) return null
        val total = fileSizeBytes * 8L * 1000L / durationMillis
        return (total - AUDIO_ESTIMATE).coerceIn(MIN, MAX).toInt()
    }
}
