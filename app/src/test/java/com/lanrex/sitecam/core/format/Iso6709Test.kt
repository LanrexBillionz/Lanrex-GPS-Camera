package com.lanrex.sitecam.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso6709Test {

    @Test
    fun samsungStyle() {
        val p = Iso6709.parse("+07.6452+004.1678/")!!
        assertEquals(7.6452, p.latitude, 1e-9)
        assertEquals(4.1678, p.longitude, 1e-9)
        assertNull(p.altitude)
    }

    @Test
    fun negativeAndAltitude() {
        val p = Iso6709.parse("-33.8688-070.6693+012.300/")!!
        assertEquals(-33.8688, p.latitude, 1e-9)
        assertEquals(-70.6693, p.longitude, 1e-9)
        assertEquals(12.3, p.altitude!!, 1e-9)
    }

    @Test
    fun invalid() {
        assertNull(Iso6709.parse(null))
        assertNull(Iso6709.parse(""))
        assertNull(Iso6709.parse("+00.0000+000.0000/"))
        assertNull(Iso6709.parse("hello"))
    }
}
