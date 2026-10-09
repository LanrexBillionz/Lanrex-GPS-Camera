package com.lanrex.sitecam.core.stamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampLayoutTest {

    /** Monospace-ish fake: every character is 0.55 x the font size wide. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String, sizePx: Float, bold: Boolean): Float =
            text.codePointCount(0, text.length) * sizePx * (if (bold) 0.58f else 0.55f)

        override fun capHeight(sizePx: Float, bold: Boolean): Float = sizePx * 0.711f
        override fun descent(sizePx: Float, bold: Boolean): Float = sizePx * 0.244f
    }

    private val lines = StampLines(
        title = "Iwo, Osun, Nigeria 🇳🇬",
        address = "183 Ibadan - Iwo Rd, Iwo, Osun 232102, Nigeria",
        coordinates = "Lat 7.645066° Long 4.167863°",
        time = "Friday, 09/10/2026 04:25 PM GMT +01:00",
    )

    @Test
    fun proportionsFollowTheShorterSide() {
        val g = StampLayout.compute(4000, 3000, lines, showMap = true, brand = "SiteCam", measurer = measurer)
        val s = 3000f
        assertEquals(0.90f * s, g.bounds.width, 0.5f)
        assertEquals(0.22f * s, g.box.height, 0.5f)
        assertEquals(g.box.height, g.map!!.height, 0.01f)
        assertEquals(g.map!!.width, g.map!!.height, 0.01f)
        assertEquals(0.02f * s, g.box.left - g.map!!.right, 0.5f)
        assertEquals(0.02f * s, 3000f - g.box.bottom, 0.5f)
        assertEquals(0.04f * s, g.tab.height, 0.5f)
        // Centred horizontally.
        assertEquals(2000f, (g.map!!.left + g.box.right) / 2f, 0.5f)
        // Tab sits on the top-right edge of the box.
        assertEquals(g.box.right, g.tab.right, 0.01f)
        assertTrue(g.tab.bottom <= g.box.top)
        assertEquals(0.045f * s, g.lines[0].sizePx, 0.5f)
        assertEquals(0.03f * s, g.lines[1].sizePx, 0.5f)
    }

    @Test
    fun portraitAndLandscapeGetTheSameStamp() {
        val landscape = StampLayout.compute(4000, 3000, lines, true, "SiteCam", measurer)
        val portrait = StampLayout.compute(3000, 4000, lines, true, "SiteCam", measurer)
        assertEquals(landscape.box.width, portrait.box.width, 0.01f)
        assertEquals(landscape.box.height, portrait.box.height, 0.01f)
        assertEquals(landscape.lines.map { it.text }, portrait.lines.map { it.text })
        assertEquals(landscape.lines.map { it.sizePx }, portrait.lines.map { it.sizePx })
    }

    @Test
    fun scalesWithResolution() {
        val small = StampLayout.compute(4000, 3000, lines, true, "SiteCam", measurer)
        val huge = StampLayout.compute(16320, 12240, lines, true, "SiteCam", measurer)
        val ratio = 12240f / 3000f
        assertEquals(small.box.width * ratio, huge.box.width, 1f)
        assertEquals(small.lines[0].sizePx * ratio, huge.lines[0].sizePx, 0.5f)
    }

    @Test
    fun everythingStaysInsideTheBox() {
        val g = StampLayout.compute(3000, 4000, lines, true, "SiteCam", measurer)
        assertTextInside(g)
    }

    @Test
    fun longAddressWrapsOrShrinksButNeverOverflows() {
        val long = lines.copy(
            title = "Ibadan North-East Local Government Area, Oyo State, Federal Republic of Nigeria 🇳🇬",
            address = "Plot 17, Block C, Off Old Ibadan - Iwo Road, Opposite the New Market, Beside the Filling Station, " +
                "Iwo, Osun State 232102, Nigeria",
            note = "Project: Iwo Road rehabilitation, Section 3 drainage and kerbs, chainage 2+350 to 2+900",
        )
        val g = StampLayout.compute(4000, 3000, long, true, "SiteCam", measurer)
        assertTrue("address should wrap onto several lines", g.lines.size > 5)
        assertTextInside(g)
    }

    @Test
    fun hugeWordIsBrokenUp() {
        val weird = lines.copy(address = "X".repeat(400))
        val g = StampLayout.compute(4000, 3000, weird, true, "SiteCam", measurer)
        assertTextInside(g)
    }

    @Test
    fun withoutMapTheBoxUsesTheWholeStrip() {
        val g = StampLayout.compute(4000, 3000, lines, showMap = false, brand = "SiteCam", measurer = measurer)
        assertEquals(0.9f * 3000f, g.box.width, 0.5f)
        assertEquals(null, g.map)
    }

    @Test
    fun hiddenLinesAreSkipped() {
        val g = StampLayout.compute(4000, 3000, StampLines(coordinates = "Lat 1.000000° Long 2.000000°"), true, "SiteCam", measurer)
        assertEquals(1, g.lines.size)
        // A single line is vertically centred in the box.
        val line = g.lines[0]
        val top = line.baseline - measurer.capHeight(line.sizePx, false)
        val bottom = line.baseline + measurer.descent(line.sizePx, false)
        assertEquals(g.box.centerY, (top + bottom) / 2f, 0.5f)
    }

    @Test
    fun coneAngles() {
        val map = Box(0f, 0f, 100f, 100f)
        val north = MapDecor.cone(map, 0f)
        assertEquals(-120f, north.startAngle, 0.001f)
        assertEquals(60f, north.sweepAngle, 0.001f)
        val east = MapDecor.cone(map, 450f)
        assertEquals(-30f, east.startAngle, 0.001f)
        val pin = MapDecor.pin(map)
        assertEquals(50f, pin.tipX, 0.001f)
        assertEquals(50f, pin.tipY, 0.001f)
        assertTrue(pin.headCenterY < pin.tipY)
    }

    private fun assertTextInside(g: StampGeometry) {
        for (line in g.lines) {
            val w = measurer.width(line.text, line.sizePx, line.bold)
            assertTrue("'${line.text}' starts outside", line.x >= g.box.left)
            assertTrue("'${line.text}' too wide: ${line.x + w} > ${g.box.right}", line.x + w <= g.box.right + 0.01f)
            assertTrue("'${line.text}' above box", line.baseline - measurer.capHeight(line.sizePx, line.bold) >= g.box.top - 0.01f)
            assertTrue("'${line.text}' below box", line.baseline + measurer.descent(line.sizePx, line.bold) <= g.box.bottom + 0.01f)
        }
    }
}
