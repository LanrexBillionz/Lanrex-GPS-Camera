package com.lanrex.sitecam.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CoordinateFormatTest {

    @Test
    fun stampLineMatchesReferenceFormat() {
        assertEquals("Lat 7.645066° Long 4.167863°", CoordinateFormat.stampLine(7.645066, 4.167863))
    }

    @Test
    fun alwaysSixDecimalPlaces() {
        assertEquals("Lat 7.645201° Long 4.167810°", CoordinateFormat.stampLine(7.645201, 4.16781))
        assertEquals("Lat 10.000000° Long -3.500000°", CoordinateFormat.stampLine(10.0, -3.5))
    }

    @Test
    fun roundsToSixDecimals() {
        assertEquals("7.645067", CoordinateFormat.decimal(7.6450666))
        assertEquals("-122.084000", CoordinateFormat.decimal(-122.0839999))
    }

    @Test
    fun southAndWestAreNegative() {
        assertEquals("Lat -33.868820° Long -70.669265°", CoordinateFormat.stampLine(-33.86882, -70.669265))
    }

    @Test
    fun tinyNegativeIsNotMinusZero() {
        assertEquals("0.000000", CoordinateFormat.decimal(-0.0000001))
    }

    @Test
    fun ignoresPhoneLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // German uses ',' as decimal separator
            assertEquals("Lat 7.645066° Long 4.167863°", CoordinateFormat.stampLine(7.645066, 4.167863))
            assertEquals("± 1.5 km", CoordinateFormat.accuracy(1500f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun accuracyText() {
        assertEquals("± 5 m", CoordinateFormat.accuracy(4.6f))
        assertEquals("± 1 m", CoordinateFormat.accuracy(0.2f))
        assertEquals("± 999 m", CoordinateFormat.accuracy(999f))
        assertEquals("± 2.0 km", CoordinateFormat.accuracy(2000f))
    }

    @Test
    fun validity() {
        assertTrue(CoordinateFormat.isValid(7.645066, 4.167863))
        assertFalse(CoordinateFormat.isValid(0.0, 0.0))
        assertFalse(CoordinateFormat.isValid(null, 4.0))
        assertFalse(CoordinateFormat.isValid(91.0, 4.0))
        assertFalse(CoordinateFormat.isValid(7.0, 181.0))
        assertFalse(CoordinateFormat.isValid(Double.NaN, 4.0))
    }

    @Test
    fun distance() {
        // About 13 m between the two reference photos taken on the same road.
        val d = CoordinateFormat.distanceMeters(7.645201, 4.16781, 7.645086, 4.167809)
        assertEquals(12.8, d, 0.5)
    }
}
