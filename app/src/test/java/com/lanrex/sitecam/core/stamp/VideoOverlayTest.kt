package com.lanrex.sitecam.core.stamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOverlayTest {

    @Test
    fun placementCoversTheStampOnWholePixels() {
        val p = VideoOverlayPlacement.of(Box(108.4f, 1500.6f, 971.2f, 1879.9f), 1080, 1920)
        assertEquals(108, p.left)
        assertEquals(1500, p.top)
        assertEquals(972 - 108, p.width)
        assertEquals(1880 - 1500, p.height)
    }

    @Test
    fun anchorIsTheTopLeftCornerInMedia3Coordinates() {
        val p = VideoOverlayPlacement.of(Box(0f, 0f, 100f, 50f), 1000, 500)
        assertEquals(-1f, p.anchorX, 1e-6f)
        assertEquals(1f, p.anchorY, 1e-6f)

        val centre = VideoOverlayPlacement.of(Box(500f, 250f, 600f, 300f), 1000, 500)
        assertEquals(0f, centre.anchorX, 1e-6f)
        assertEquals(0f, centre.anchorY, 1e-6f)

        // Bottom-right quarter: x positive, y negative (Media3's y axis points up).
        val q = VideoOverlayPlacement.of(Box(750f, 375f, 800f, 400f), 1000, 500)
        assertEquals(0.5f, q.anchorX, 1e-6f)
        assertEquals(-0.5f, q.anchorY, 1e-6f)
    }

    @Test
    fun realStampLayoutFitsInsidePortraitAndLandscapeFrames() {
        val lines = StampLines(
            title = "Iwo, Osun, Nigeria 🇳🇬",
            address = "183 Ibadan - Iwo Rd, Iwo, Osun 232102, Nigeria",
            coordinates = "Lat 7.645066° Long 4.167863°",
            time = "Friday, 09/10/2026 04:25 PM GMT +01:00",
            note = null,
        )
        val measurer = object : TextMeasurer {
            override fun width(text: String, sizePx: Float, bold: Boolean) = text.length * sizePx * 0.55f
            override fun capHeight(sizePx: Float, bold: Boolean) = sizePx * 0.711f
            override fun descent(sizePx: Float, bold: Boolean) = sizePx * 0.244f
        }
        for ((w, h) in listOf(3840 to 2160, 2160 to 3840, 7680 to 4320, 1080 to 1920)) {
            val g = StampLayout.compute(w, h, lines, true, "SiteCam", measurer)
            val p = VideoOverlayPlacement.of(g.bounds, w, h)
            assertTrue(p.left >= 0 && p.top >= 0)
            assertTrue(p.left + p.width <= w && p.top + p.height <= h)
            // Same size and position as on a photo of the same shape.
            assertTrue(p.left <= g.bounds.left && p.left + p.width >= g.bounds.right)
            assertTrue(p.top <= g.bounds.top && p.top + p.height >= g.bounds.bottom)
        }
    }

    @Test
    fun bitrateKeepsTheTrackBitrateWhenKnown() {
        assertEquals(48_000_000, VideoBitrate.target(48_000_000, 1L, 1L))
    }

    @Test
    fun bitrateIsEstimatedFromSizeAndLength() {
        // 100 MB over 20 s = 40 Mbit/s in total, minus the audio track.
        assertEquals(40_000_000 - 256_000, VideoBitrate.target(null, 100_000_000L, 20_000L))
        assertEquals(VideoBitrate.MIN.toInt(), VideoBitrate.target(null, 1_000L, 60_000L))
        assertNull(VideoBitrate.target(null, 0L, 20_000L))
        assertNull(VideoBitrate.target(0, 100L, 0L))
    }
}
