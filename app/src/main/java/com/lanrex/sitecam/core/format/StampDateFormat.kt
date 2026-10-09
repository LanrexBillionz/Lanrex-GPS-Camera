package com.lanrex.sitecam.core.format

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * When a photo or video was captured.
 *
 * @param epochMillis the instant (UTC).
 * @param offsetSeconds the local time-zone offset at the place/time of capture.
 * @param offsetFromFile true when the offset was stored in the file itself
 *   (EXIF OffsetTimeOriginal), false when it was taken from the phone's zone.
 */
data class CaptureTime(
    val epochMillis: Long,
    val offsetSeconds: Int,
    val offsetFromFile: Boolean,
)

object StampDateFormat {

    /**
     * Line 4 of the stamp: "Friday, 09/10/2026 04:25 PM GMT +01:00"
     * (day name, dd/MM/yyyy, 12-hour clock). Built by hand so it never
     * depends on the phone's language or region settings.
     */
    fun stampLine(time: CaptureTime): String = stampLine(time.epochMillis, time.offsetSeconds)

    fun stampLine(epochMillis: Long, offsetSeconds: Int): String {
        val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val t = Instant.ofEpochMilli(epochMillis).atOffset(offset)
        val hour12 = when (val h = t.hour % 12) { 0 -> 12; else -> h }
        val amPm = if (t.hour < 12) "AM" else "PM"
        return buildString {
            append(dayName(t.dayOfWeek)).append(", ")
            append(two(t.dayOfMonth)).append('/').append(two(t.monthValue)).append('/').append(t.year)
            append(' ').append(two(hour12)).append(':').append(two(t.minute)).append(' ').append(amPm)
            append(" GMT ").append(offsetText(offsetSeconds))
        }
    }

    /** "+01:00", "-05:30", "+00:00" */
    fun offsetText(offsetSeconds: Int): String {
        val sign = if (offsetSeconds < 0) '-' else '+'
        val total = abs(offsetSeconds) / 60
        return "$sign${two(total / 60)}:${two(total % 60)}"
    }

    /** Offset of [zone] at [epochMillis], in seconds. */
    fun zoneOffsetSeconds(epochMillis: Long, zone: ZoneId): Int =
        zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds

    /**
     * Parses EXIF DateTimeOriginal ("2026:10:09 16:49:00", local time) with the
     * optional OffsetTimeOriginal ("+01:00") and SubSecTimeOriginal ("123").
     * When the offset is missing, [fallbackZone]'s offset for that local time is used.
     */
    fun parseExif(
        dateTime: String?,
        offset: String?,
        subSec: String? = null,
        fallbackZone: ZoneId,
    ): CaptureTime? {
        val local = parseExifLocal(dateTime) ?: return null
        val millis = parseSubSecMillis(subSec)
        val localWithMs = local.plusNanos(millis * 1_000_000L)
        val parsedOffset = parseOffset(offset)
        return try {
            if (parsedOffset != null) {
                val instant = localWithMs.toInstant(parsedOffset)
                CaptureTime(instant.toEpochMilli(), parsedOffset.totalSeconds, offsetFromFile = true)
            } else {
                val zoned = localWithMs.atZone(fallbackZone)
                CaptureTime(zoned.toInstant().toEpochMilli(), zoned.offset.totalSeconds, offsetFromFile = false)
            }
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * Parses the date MediaMetadataRetriever reports for videos, e.g.
     * "20261009T154900.000Z" (always UTC on Samsung). Returns null for the
     * MP4 "unset" date (1904/1970) and anything unreadable.
     */
    fun parseMp4Date(value: String?): Instant? {
        val m = MP4_DATE.matchEntire(value?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val year = y.toInt()
        if (year < 1980) return null
        return try {
            val local = LocalDateTime.of(year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            val zone = m.groupValues[8]
            val offset = if (zone.isEmpty() || zone == "Z") ZoneOffset.UTC else parseOffset(zone) ?: ZoneOffset.UTC
            local.toInstant(offset)
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun parseExifLocal(value: String?): LocalDateTime? {
        val m = EXIF_DATE.matchEntire(value?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val year = y.toInt()
        if (year < 1900) return null
        return try {
            LocalDateTime.of(year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    /** Accepts "+01:00", "-0530", "+01", "Z". */
    fun parseOffset(value: String?): ZoneOffset? {
        val v = value?.trim() ?: return null
        if (v == "Z") return ZoneOffset.UTC
        val m = OFFSET.matchEntire(v) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (hours > 14 || minutes > 59) return null
        return try {
            ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun parseSubSecMillis(subSec: String?): Long {
        val digits = subSec?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return 0
        return digits.padEnd(3, '0').take(3).toLong()
    }

    private fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    private val EXIF_DATE = Regex("""(\d{4})[:\-/](\d{2})[:\-/](\d{2})[ T](\d{2}):(\d{2}):(\d{2}).*""")
    private val MP4_DATE = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")
    private val OFFSET = Regex("""([+-])(\d{2}):?(\d{2})?""")
}
