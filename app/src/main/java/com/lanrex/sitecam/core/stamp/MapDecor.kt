package com.lanrex.sitecam.core.stamp

/**
 * Geometry of the red pin and the blue "camera facing" cone drawn on the map
 * thumbnail. Shared by the Android renderer and the tests.
 */
object MapDecor {

    /** Teardrop pin whose tip touches the exact location (the map centre). */
    data class Pin(
        val tipX: Float,
        val tipY: Float,
        val headCenterX: Float,
        val headCenterY: Float,
        val headRadius: Float,
        val dotRadius: Float,
        val strokeWidth: Float,
    )

    /**
     * Wedge from the location towards the compass heading.
     * Angles follow Android Canvas: 0° points right (east) and angles grow clockwise.
     */
    data class Cone(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val startAngle: Float,
        val sweepAngle: Float,
    )

    const val PIN_HEIGHT = 0.20f
    const val CONE_RADIUS = 0.42f
    const val CONE_SPREAD_DEGREES = 60f

    const val PIN_COLOR_ARGB = 0xFFEA4335.toInt()
    const val PIN_STROKE_ARGB = 0xFFB31412.toInt()
    const val PIN_DOT_ARGB = 0xFF7A0F0C.toInt()
    const val CONE_COLOR_ARGB = 0x4D1A73E8 // translucent blue
    const val CONE_EDGE_ARGB = 0x991A73E8.toInt()
    const val PLACEHOLDER_BG_ARGB = 0xFFDCE3D6.toInt()
    const val PLACEHOLDER_GRID_ARGB = 0xFFC3CCBB.toInt()

    fun pin(map: Box): Pin {
        val size = map.width
        val height = size * PIN_HEIGHT
        val r = height * 0.36f
        val tipX = map.centerX
        val tipY = map.centerY
        return Pin(
            tipX = tipX,
            tipY = tipY,
            headCenterX = tipX,
            headCenterY = tipY - height + r,
            headRadius = r,
            dotRadius = r * 0.38f,
            strokeWidth = r * 0.12f,
        )
    }

    /** [headingDegrees]: 0 = north, 90 = east (true north, clockwise). */
    fun cone(map: Box, headingDegrees: Float): Cone {
        val normalized = ((headingDegrees % 360f) + 360f) % 360f
        return Cone(
            centerX = map.centerX,
            centerY = map.centerY,
            radius = map.width * CONE_RADIUS,
            startAngle = normalized - 90f - CONE_SPREAD_DEGREES / 2f,
            sweepAngle = CONE_SPREAD_DEGREES,
        )
    }
}
