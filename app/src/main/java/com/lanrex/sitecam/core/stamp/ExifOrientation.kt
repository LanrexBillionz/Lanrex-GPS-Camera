package com.lanrex.sitecam.core.stamp

/**
 * EXIF orientation helpers. Cameras often store a portrait photo sideways and
 * add an "Orientation" tag that tells viewers to rotate it. SiteCam keeps the
 * pixels exactly as stored (and copies the tag), so the stamp is drawn rotated
 * into the stored pixels and appears upright and at the bottom when viewed.
 */
object ExifOrientation {
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    fun isTransposed(orientation: Int): Boolean = orientation in TRANSPOSE..ROTATE_270

    /** Size of the image as it is viewed. */
    fun displaySize(orientation: Int, storedWidth: Int, storedHeight: Int): Pair<Int, Int> =
        if (isTransposed(orientation)) storedHeight to storedWidth else storedWidth to storedHeight

    /**
     * Affine transform mapping a point in display coordinates to stored pixel
     * coordinates, as [a, b, c, d, e, f] with x' = a*x + b*y + c, y' = d*x + e*y + f.
     */
    fun storedFromDisplay(orientation: Int, storedWidth: Int, storedHeight: Int): FloatArray {
        val w = storedWidth.toFloat()
        val h = storedHeight.toFloat()
        return when (orientation) {
            FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
            ROTATE_180 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
            FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
            TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
            ROTATE_90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, h)
            TRANSVERSE -> floatArrayOf(0f, -1f, w, -1f, 0f, h)
            ROTATE_270 -> floatArrayOf(0f, -1f, w, 1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }
    }

    /** Maps a display-space rectangle to the stored-space rows it touches: [top, bottom). */
    fun storedRows(orientation: Int, storedWidth: Int, storedHeight: Int, box: Box): IntRange {
        val m = storedFromDisplay(orientation, storedWidth, storedHeight)
        val corners = listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
        val ys = corners.map { (x, y) -> m[3] * x + m[4] * y + m[5] }
        val top = kotlin.math.floor(ys.min()).toInt().coerceIn(0, storedHeight)
        val bottom = kotlin.math.ceil(ys.max()).toInt().coerceIn(0, storedHeight)
        return top until bottom
    }
}
