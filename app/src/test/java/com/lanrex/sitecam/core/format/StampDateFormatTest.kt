package com.lanrex.sitecam.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class StampDateFormatTest {

    private val lagos = ZoneId.of("Africa/Lagos")

    @Test
    fun stampLineMatchesReferenceFormat() {
        val t = StampDateFormat.parseExif("2026:10:09 16:25:00", "+01:00", null, lagos)!!
        assertEquals("Friday, 09/10/2026 04:25 PM GMT +01:00", StampDateFormat.stampLine(t))
    }

    @Test
    fun morningAndMidnight() {
        val morning = StampDateFormat.parseExif("2026:10:01 09:05:00", "+01:00", null, lagos)!!
        assertEquals("Thursday, 01/10/2026 09:05 AM GMT +01:00", StampDateFormat.stampLine(morning))
        val midnight = StampDateFormat.parseExif("2026:10:10 00:30:00", "+01:00", null, lagos)!!
        assertEquals("Saturday, 10/10/2026 12:30 AM GMT +01:00", StampDateFormat.stampLine(midnight))
        val noon = StampDateFormat.parseExif("2026:10:10 12:00:00", "+01:00", null, lagos)!!
        assertEquals("Saturday, 10/10/2026 12:00 PM GMT +01:00", StampDateFormat.stampLine(noon))
    }

    @Test
    fun negativeAndHalfHourOffsets() {
        val ny = StampDateFormat.parseExif("2026:01:15 18:45:10", "-05:00", null, lagos)!!
        assertEquals("Thursday, 15/01/2026 06:45 PM GMT -05:00", StampDateFormat.stampLine(ny))
        val india = StampDateFormat.parseExif("2026:01:15 18:45:10", "+05:30", null, lagos)!!
        assertEquals("Thursday, 15/01/2026 06:45 PM GMT +05:30", StampDateFormat.stampLine(india))
        assertEquals("+00:00", StampDateFormat.offsetText(0))
    }

    @Test
    fun exifOffsetGivesTheRightInstant() {
        val t = StampDateFormat.parseExif("2026:10:09 16:49:00", "+01:00", "120", lagos)!!
        assertEquals(Instant.parse("2026-10-09T15:49:00.120Z").toEpochMilli(), t.epochMillis)
        assertEquals(3600, t.offsetSeconds)
        assertTrue(t.offsetFromFile)
    }

    @Test
    fun missingOffsetUsesPhoneZone() {
        val t = StampDateFormat.parseExif("2026:10:09 16:49:00", null, null, lagos)!!
        assertEquals(Instant.parse("2026-10-09T15:49:00Z").toEpochMilli(), t.epochMillis)
        assertEquals(3600, t.offsetSeconds)
        assertFalse(t.offsetFromFile)

        val london = StampDateFormat.parseExif("2026:07:01 10:00:00", "   :  ", null, ZoneId.of("Europe/London"))!!
        assertEquals(3600, london.offsetSeconds) // British Summer Time
    }

    @Test
    fun badExifDates() {
        assertNull(StampDateFormat.parseExif(null, null, null, lagos))
        assertNull(StampDateFormat.parseExif("    :  :     :  :  ", null, null, lagos))
        assertNull(StampDateFormat.parseExif("0000:00:00 00:00:00", null, null, lagos))
        assertNull(StampDateFormat.parseExif("2026:13:40 10:00:00", null, null, lagos))
    }

    @Test
    fun dashedExifDateIsAccepted() {
        val t = StampDateFormat.parseExif("2026-10-09 16:25:00", "+01:00", null, lagos)
        assertEquals("Friday, 09/10/2026 04:25 PM GMT +01:00", StampDateFormat.stampLine(t!!))
    }

    @Test
    fun mp4Dates() {
        assertEquals(Instant.parse("2026-10-09T15:49:00Z"), StampDateFormat.parseMp4Date("20261009T154900.000Z"))
        assertEquals(Instant.parse("2026-10-09T15:49:00Z"), StampDateFormat.parseMp4Date("20261009T154900Z"))
        assertEquals(Instant.parse("2026-10-09T15:49:00Z"), StampDateFormat.parseMp4Date("20261009T164900.000+01:00"))
        assertNull(StampDateFormat.parseMp4Date("19040101T000000.000Z"))
        assertNull(StampDateFormat.parseMp4Date("garbage"))
        assertNull(StampDateFormat.parseMp4Date(null))
    }

    @Test
    fun videoTimeInPhoneZone() {
        val instant = StampDateFormat.parseMp4Date("20261009T153800.000Z")!!
        val offset = StampDateFormat.zoneOffsetSeconds(instant.toEpochMilli(), lagos)
        assertEquals("Friday, 09/10/2026 04:38 PM GMT +01:00", StampDateFormat.stampLine(instant.toEpochMilli(), offset))
    }

    @Test
    fun offsets() {
        assertEquals(ZoneOffset.ofHours(1), StampDateFormat.parseOffset("+01:00"))
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), StampDateFormat.parseOffset("-0530"))
        assertEquals(ZoneOffset.UTC, StampDateFormat.parseOffset("Z"))
        assertNull(StampDateFormat.parseOffset("   :  "))
        assertNull(StampDateFormat.parseOffset(""))
    }

    @Test
    fun ignoresPhoneLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val t = StampDateFormat.parseExif("2026:10:09 16:25:00", "+01:00", null, lagos)!!
            assertEquals("Friday, 09/10/2026 04:25 PM GMT +01:00", StampDateFormat.stampLine(t))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
