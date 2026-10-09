package com.lanrex.sitecam.location

import android.location.Location
import android.os.SystemClock
import com.lanrex.sitecam.core.format.CoordinateFormat

/** A GPS position from the phone's location service. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitude: Double?,
    /** Wall-clock time of the fix (ms since 1970). */
    val timeMillis: Long,
    /** Boot-relative time of the fix, used to measure its age reliably. */
    val elapsedRealtimeNanos: Long,
) {
    fun ageMillis(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L

    fun isFresh(maxAgeMillis: Long = MAX_FIX_AGE_MS): Boolean = ageMillis() <= maxAgeMillis

    companion object {
        /** Fixes older than this are ignored everywhere in the app. */
        const val MAX_FIX_AGE_MS = 30_000L

        fun from(location: Location): GpsFix? {
            if (!CoordinateFormat.isValid(location.latitude, location.longitude)) return null
            return GpsFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                altitude = if (location.hasAltitude()) location.altitude else null,
                timeMillis = location.time,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            )
        }
    }
}
