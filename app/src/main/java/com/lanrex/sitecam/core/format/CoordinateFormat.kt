package com.lanrex.sitecam.core.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats GPS coordinates for the stamp and the app screens.
 *
 * Always uses a '.' decimal separator, whatever language the phone is set to,
 * so stamps look the same everywhere.
 */
object CoordinateFormat {

    /** "Lat 7.645066° Long 4.167863°" */
    fun stampLine(latitude: Double, longitude: Double): String =
        "Lat ${decimal(latitude)}° Long ${decimal(longitude)}°"

    /** Six decimal places (about 11 cm), e.g. "7.645066" or "-33.868820". */
    fun decimal(value: Double): String {
        val text = String.format(Locale.US, "%.6f", value)
        // Avoid printing "-0.000000" for tiny negative values.
        return if (text == "-0.000000") "0.000000" else text
    }

    /** "± 5 m", "± 12 m", "± 1.2 km" */
    fun accuracy(meters: Float): String {
        if (meters.isNaN() || meters < 0f) return "± ? m"
        return if (meters < 1000f) {
            "± ${meters.roundToInt().coerceAtLeast(1)} m"
        } else {
            "± ${String.format(Locale.US, "%.1f", meters / 1000f)} km"
        }
    }

    /**
     * True when the pair looks like a real position. Cameras write 0,0 when they
     * had no fix, so that is treated as "no location".
     */
    fun isValid(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        return !(abs(latitude) < 1e-7 && abs(longitude) < 1e-7)
    }

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
