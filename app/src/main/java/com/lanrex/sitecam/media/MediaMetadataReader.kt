package com.lanrex.sitecam.media

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.GeomagneticField
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lanrex.sitecam.core.format.CaptureTime
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.core.format.Iso6709
import com.lanrex.sitecam.core.format.StampDateFormat
import java.io.IOException
import java.time.ZoneId

/** What SiteCam learns from a photo file. */
data class PhotoInfo(
    /** Pixel size as stored in the file (before the EXIF rotation is applied). */
    val storedWidth: Int,
    val storedHeight: Int,
    val exifOrientation: Int,
    val captureTime: CaptureTime?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    /** Compass direction the camera faced (true north), if the camera saved it. */
    val headingDegrees: Float?,
    val exif: ExifInterface?,
) {
    val hasLocation: Boolean get() = CoordinateFormat.isValid(latitude, longitude)
}

/** What SiteCam learns from a video file. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMillis: Long,
    val captureTime: CaptureTime?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val videoMime: String?,
    val videoBitrate: Int?,
    val frameRate: Float?,
    val isHdr: Boolean,
    val hasAudio: Boolean,
) {
    val hasLocation: Boolean get() = CoordinateFormat.isValid(latitude, longitude)
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width
}

class MediaMetadataReader(private val context: Context) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun readPhoto(uri: Uri, fallbackDateTakenMillis: Long?): PhotoInfo {
        val exif = openExif(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            // Size falls back to EXIF below.
        }
        var width = bounds.outWidth
        var height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            width = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)?.takeIf { it > 0 }
                ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
            height = exif?.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)?.takeIf { it > 0 }
                ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
        }
        if (width <= 0 || height <= 0) throw IOException("This file is not a readable photo.")

        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val latLong = exif?.latLong
        val lat = latLong?.getOrNull(0)
        val lon = latLong?.getOrNull(1)
        val valid = CoordinateFormat.isValid(lat, lon)
        val altitude = exif?.getAltitude(Double.NaN)?.takeIf { !it.isNaN() }
        val time = captureTime(exif, fallbackDateTakenMillis)

        return PhotoInfo(
            storedWidth = width,
            storedHeight = height,
            exifOrientation = orientation,
            captureTime = time,
            latitude = if (valid) lat else null,
            longitude = if (valid) lon else null,
            altitude = if (valid) altitude else null,
            headingDegrees = if (valid) heading(exif, lat!!, lon!!, altitude, time) else null,
            exif = exif,
        )
    }

    /** Reads EXIF through a file descriptor (needed for HEIC), falling back to a stream. */
    fun openExif(uri: Uri): ExifInterface? {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { return ExifInterface(it.fileDescriptor) }
        } catch (e: Exception) {
            // Try a plain stream below.
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun captureTime(exif: ExifInterface?, fallbackDateTakenMillis: Long?): CaptureTime? {
        if (exif != null) {
            StampDateFormat.parseExif(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                zone,
            )?.let { return it }
            StampDateFormat.parseExif(
                exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
                null,
                zone,
            )?.let { return it }
            StampDateFormat.parseExif(
                exif.getAttribute(ExifInterface.TAG_DATETIME),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
                null,
                zone,
            )?.let { return it }
        }
        return fallbackDateTakenMillis?.let { fromMillis(it) }
    }

    /** GPSImgDirection converted to true north. */
    private fun heading(exif: ExifInterface?, lat: Double, lon: Double, altitude: Double?, time: CaptureTime?): Float? {
        exif ?: return null
        val direction = exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, -1.0)
        if (direction < 0 || direction > 360) return null
        val ref = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF)
        return if (ref.equals("M", ignoreCase = true)) {
            val field = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                (altitude ?: 0.0).toFloat(),
                time?.epochMillis ?: System.currentTimeMillis(),
            )
            ((direction + field.declination + 360.0) % 360.0).toFloat()
        } else {
            direction.toFloat()
        }
    }

    fun readVideo(uri: Uri, fallbackDateTakenMillis: Long?): VideoInfo {
        val retriever = MediaMetadataRetriever()
        var width = 0
        var height = 0
        var rotation = 0
        var duration = 0L
        var location: Iso6709.Position? = null
        var date: java.time.Instant? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open video")
            pfd.use {
                retriever.setDataSource(it.fileDescriptor)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                location = Iso6709.parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                date = StampDateFormat.parseMp4Date(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        if (width <= 0 || height <= 0 || duration <= 0) {
            throw IOException("This video is not readable (it may still be recording).")
        }

        var videoMime: String? = null
        var bitrate: Int? = null
        var frameRate: Float? = null
        var hdr = false
        var hasAudio = false
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) hasAudio = true
                    if (!mime.startsWith("video/") || videoMime != null) continue
                    videoMime = mime
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = try {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (e: ClassCastException) {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        }
                    }
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                    }
                }
            }
        } catch (e: Exception) {
            // Track details are optional; Transformer works without them. Assume the usual audio track.
            hasAudio = true
        } finally {
            extractor.release()
        }

        val captured = date?.let { fromMillis(it.toEpochMilli()) } ?: fallbackDateTakenMillis?.let { fromMillis(it) }
        val loc = location
        return VideoInfo(
            width = width,
            height = height,
            rotationDegrees = ((rotation % 360) + 360) % 360,
            durationMillis = duration,
            captureTime = captured,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            altitude = loc?.altitude,
            videoMime = videoMime,
            videoBitrate = bitrate,
            frameRate = frameRate,
            isHdr = hdr,
            hasAudio = hasAudio,
        )
    }

    private fun fromMillis(millis: Long) =
        CaptureTime(millis, StampDateFormat.zoneOffsetSeconds(millis, zone), offsetFromFile = false)
}
