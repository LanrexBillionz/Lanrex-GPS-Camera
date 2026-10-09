package com.lanrex.sitecam.stamp

import com.lanrex.sitecam.core.format.CaptureTime
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.core.format.StampDateFormat
import com.lanrex.sitecam.core.stamp.StampLines
import com.lanrex.sitecam.data.StampSettings
import com.lanrex.sitecam.location.AddressLookup
import java.time.Instant
import java.time.ZoneId

/** Builds the stamp's text lines from the settings, the address and the capture time. */
object StampContent {

    fun lines(
        settings: StampSettings,
        address: AddressLookup?,
        latitude: Double,
        longitude: Double,
        time: CaptureTime,
    ): StampLines {
        // While offline the address lines are left out; the copy is re-stamped
        // automatically once the address can be looked up.
        val found = address as? AddressLookup.Found
        return StampLines(
            title = if (settings.showTitle) found?.title else null,
            address = if (settings.showAddress) found?.fullAddress else null,
            coordinates = if (settings.showCoordinates) CoordinateFormat.stampLine(latitude, longitude) else null,
            time = if (settings.showTime) StampDateFormat.stampLine(time) else null,
            note = if (settings.noteEnabled) settings.noteText.trim().ifEmpty { null } else null,
        )
    }

    /** "2026:10:09 16:49:00" in the capture's local time, for EXIF. */
    fun exifDateTime(time: CaptureTime): String {
        val t = Instant.ofEpochMilli(time.epochMillis).atOffset(java.time.ZoneOffset.ofTotalSeconds(time.offsetSeconds))
        return String.format(
            java.util.Locale.US,
            "%04d:%02d:%02d %02d:%02d:%02d",
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second,
        )
    }

    fun now(): CaptureTime {
        val millis = System.currentTimeMillis()
        return CaptureTime(millis, StampDateFormat.zoneOffsetSeconds(millis, ZoneId.systemDefault()), false)
    }
}

/** The file has no usable location and the user has not chosen one yet. */
class NeedsLocationException : Exception("No location in this file")

/** RAW files and other formats SiteCam does not stamp. */
class UnsupportedMediaException(message: String) : Exception(message)
