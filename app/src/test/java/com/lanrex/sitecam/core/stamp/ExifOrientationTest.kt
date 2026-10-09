package com.lanrex.sitecam.core.stamp

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {

    private fun map(o: Int, w: Int, h: Int, x: Float, y: Float): Pair<Float, Float> {
        val m = ExifOrientation.storedFromDisplay(o, w, h)
        return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
    }

    @Test
    fun displaySize() {
        assertEquals(4000 to 3000, ExifOrientation.displaySize(1, 4000, 3000))
        assertEquals(3000 to 4000, ExifOrientation.displaySize(6, 4000, 3000))
        assertEquals(3000 to 4000, ExifOrientation.displaySize(8, 4000, 3000))
    }

    @Test
    fun rotate90() {
        // Stored 4000x3000 sideways; turning it 90° clockwise gives the 3000x4000 portrait view.
        // Viewed top-left = stored bottom-left.
        assertEquals(0f to 3000f, map(6, 4000, 3000, 0f, 0f))
        // Viewed bottom-left = stored bottom-right.
        assertEquals(4000f to 3000f, map(6, 4000, 3000, 0f, 4000f))
        // Viewed bottom-right = stored top-right.
        assertEquals(4000f to 0f, map(6, 4000, 3000, 3000f, 4000f))
    }

    @Test
    fun rotate270() {
        // Viewed top-left = stored top-right.
        assertEquals(4000f to 0f, map(8, 4000, 3000, 0f, 0f))
        // Viewed bottom-left = stored top-left.
        assertEquals(0f to 0f, map(8, 4000, 3000, 0f, 4000f))
    }

    @Test
    fun rotate180AndMirrors() {
        assertEquals(4000f to 3000f, map(3, 4000, 3000, 0f, 0f))
        assertEquals(4000f to 0f, map(2, 4000, 3000, 0f, 0f))
        assertEquals(0f to 3000f, map(4, 4000, 3000, 0f, 0f))
        assertEquals(0f to 0f, map(5, 4000, 3000, 0f, 0f))
        assertEquals(4000f to 3000f, map(7, 4000, 3000, 0f, 0f))
    }

    @Test
    fun stampRowsForRotatedPhoto() {
        // A stamp along the bottom of a viewed portrait photo sits along the left edge
        // of the stored landscape pixels, so it touches every stored row.
        val stampBox = Box(150f, 3000f, 2850f, 3920f)
        val rows = ExifOrientation.storedRows(6, 4000, 3000, stampBox)
        assertEquals(150, rows.first)
        assertEquals(2849, rows.last)
        // Unrotated: only the bottom rows.
        val plain = ExifOrientation.storedRows(1, 3000, 4000, stampBox)
        assertEquals(3000, plain.first)
        assertEquals(3919, plain.last)
    }
}
