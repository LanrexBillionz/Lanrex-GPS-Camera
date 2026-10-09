package com.lanrex.sitecam.core.format

/**
 * Parses the ISO 6709 location string that Android reports for videos
 * (MediaMetadataRetriever.METADATA_KEY_LOCATION), e.g. "+07.6452+004.1678/"
 * or "+37.4219-122.0840+012.300/".
 */
object Iso6709 {

    data class Position(val latitude: Double, val longitude: Double, val altitude: Double?)

    fun parse(value: String?): Position? {
        val text = value?.trim()?.removeSuffix("/") ?: return null
        val m = PATTERN.matchEntire(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        val alt = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (!CoordinateFormat.isValid(lat, lon)) return null
        return Position(lat, lon, alt)
    }

    private val PATTERN = Regex("""([+-]\d{1,2}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?(?:CRS.*)?""")
}
