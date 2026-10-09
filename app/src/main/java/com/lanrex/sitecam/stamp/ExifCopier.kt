package com.lanrex.sitecam.stamp

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Copies the original photo's EXIF data into the stamped JPEG.
 *
 * Every standard tag is copied (camera, lens, exposure, dates and time zones, GPS, ...).
 * Binary "undefined" tags such as Samsung's private MakerNote are left out because
 * ExifInterface cannot write them back without corrupting them. Structural tags
 * (thumbnail, strips, compression) belong to the new file and are not copied.
 */
object ExifCopier {

    private val TAGS = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_WHITE_POINT,
        ExifInterface.TAG_PRIMARY_CHROMATICITIES,
        ExifInterface.TAG_GAMMA,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
        ExifInterface.TAG_RELATED_SOUND_FILE,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_SPECTRAL_SENSITIVITY,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
        ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_EXPOSURE_INDEX,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_CUSTOM_RENDERED,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_INTEROPERABILITY_INDEX,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    data class Overrides(
        /** Location chosen in SiteCam for a photo that had none. */
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
        /** Used when the source had no EXIF at all. */
        val dateTimeOriginal: String? = null,
        val offsetTimeOriginal: String? = null,
        /** Orientation of the pixels SiteCam wrote. */
        val orientation: Int? = null,
    )

    fun copy(source: ExifInterface?, target: File, overrides: Overrides) {
        val out = ExifInterface(target.absolutePath)
        if (source != null) {
            for (tag in TAGS) {
                val value = try {
                    source.getAttribute(tag)
                } catch (e: Exception) {
                    null
                }
                if (!value.isNullOrEmpty()) {
                    try {
                        out.setAttribute(tag, value)
                    } catch (e: Exception) {
                        // A malformed value in the original is simply skipped.
                    }
                }
            }
        }
        if (overrides.latitude != null && overrides.longitude != null) {
            out.setLatLong(overrides.latitude, overrides.longitude)
            overrides.altitude?.let { out.setAltitude(it) }
        }
        if (source?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrEmpty()) {
            overrides.dateTimeOriginal?.let { out.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
            overrides.offsetTimeOriginal?.let { out.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it) }
        }
        overrides.orientation?.let { out.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
        // SiteCam writes standard sRGB pixels.
        out.setAttribute(ExifInterface.TAG_COLOR_SPACE, ExifInterface.COLOR_SPACE_S_RGB.toString())
        if (out.getAttribute(ExifInterface.TAG_SOFTWARE).isNullOrEmpty()) {
            out.setAttribute(ExifInterface.TAG_SOFTWARE, "SiteCam")
        }
        out.saveAttributes()
    }
}
