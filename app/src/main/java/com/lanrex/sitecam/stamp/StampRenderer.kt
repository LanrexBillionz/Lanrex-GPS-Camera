package com.lanrex.sitecam.stamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.lanrex.sitecam.core.stamp.Box
import com.lanrex.sitecam.core.stamp.MapDecor
import com.lanrex.sitecam.core.stamp.StampGeometry
import com.lanrex.sitecam.core.stamp.StampLayout
import com.lanrex.sitecam.core.stamp.StampLines
import com.lanrex.sitecam.core.stamp.TextMeasurer

/** The map thumbnail to draw: a downloaded satellite image, or the plain placeholder. */
sealed interface MapTile {
    data class Image(val bitmap: Bitmap) : MapTile
    data object Placeholder : MapTile
}

/**
 * Draws the stamp with the bundled Roboto font, so it never depends on the
 * phone's font setting. Coordinates are display-orientation image pixels.
 */
class StampRenderer(context: Context) {

    private val regular: Typeface = Typeface.createFromAsset(context.assets, "fonts/Roboto-Regular.ttf")
    private val bold: Typeface = Typeface.createFromAsset(context.assets, "fonts/Roboto-Bold.ttf")

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    val measurer: TextMeasurer = object : TextMeasurer {
        override fun width(text: String, sizePx: Float, bold: Boolean): Float = synchronized(measurePaint) {
            measurePaint.typeface = if (bold) this@StampRenderer.bold else regular
            measurePaint.textSize = sizePx
            measurePaint.measureText(text)
        }

        // Roboto's capital height is 0.711 em; its descender is 0.244 em.
        override fun capHeight(sizePx: Float, bold: Boolean): Float = sizePx * 0.711f
        override fun descent(sizePx: Float, bold: Boolean): Float = sizePx * 0.244f
    }

    fun layout(width: Int, height: Int, lines: StampLines, showMap: Boolean): StampGeometry =
        StampLayout.compute(width, height, lines, showMap, BRAND, measurer)

    /** Draws the whole stamp. The canvas may be translated/rotated by the caller. */
    fun draw(canvas: Canvas, g: StampGeometry, map: MapTile?, headingDegrees: Float?) {
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StampLayout.BOX_COLOR_ARGB }
        canvas.drawRoundRect(g.box.rect(), g.boxRadius, g.boxRadius, boxPaint)
        canvas.drawRoundRect(g.tab.rect(), g.tabRadius, g.tabRadius, boxPaint)
        drawLogo(canvas, g.tabIcon)

        if (g.map != null) drawMap(canvas, g.map, g.mapRadius, map ?: MapTile.Placeholder, headingDegrees)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.WHITE }
        for (line in g.lines + g.tabLabel) {
            textPaint.typeface = if (line.bold) bold else regular
            textPaint.textSize = line.sizePx
            canvas.drawText(line.text, line.x, line.baseline, textPaint)
        }
    }

    /** Renders only the stamp into a transparent bitmap (used for videos and previews). */
    fun renderStampBitmap(g: StampGeometry, map: MapTile?, headingDegrees: Float?): Bitmap {
        val b = g.bounds
        val bitmap = Bitmap.createBitmap(
            kotlin.math.ceil(b.width).toInt().coerceAtLeast(1),
            kotlin.math.ceil(b.height).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.translate(-b.left, -b.top)
        draw(canvas, g, map, headingDegrees)
        return bitmap
    }

    private fun drawMap(canvas: Canvas, box: Box, radius: Float, tile: MapTile, headingDegrees: Float?) {
        val rect = box.rect()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        when (tile) {
            is MapTile.Image -> {
                val bitmap = downscaleFor(tile.bitmap, rect.width())
                val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(
                    Matrix().apply {
                        setRectToRect(
                            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                            rect,
                            Matrix.ScaleToFit.FILL,
                        )
                    },
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            MapTile.Placeholder -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapDecor.PLACEHOLDER_BG_ARGB }
                canvas.drawRoundRect(rect, radius, radius, bg)
                canvas.save()
                canvas.clipPath(clip)
                val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = MapDecor.PLACEHOLDER_GRID_ARGB
                    strokeWidth = rect.width() * 0.01f
                }
                for (k in 1..5) {
                    val x = rect.left + rect.width() * k / 6f
                    val y = rect.top + rect.height() * k / 6f
                    canvas.drawLine(x, rect.top, x, rect.bottom, grid)
                    canvas.drawLine(rect.left, y, rect.right, y, grid)
                }
                canvas.restore()
            }
        }

        if (headingDegrees != null) {
            val cone = MapDecor.cone(box, headingDegrees)
            canvas.save()
            canvas.clipPath(clip)
            val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cone.centerX,
                    cone.centerY,
                    cone.radius,
                    intArrayOf(MapDecor.CONE_EDGE_ARGB, MapDecor.CONE_COLOR_ARGB),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val oval = RectF(
                cone.centerX - cone.radius,
                cone.centerY - cone.radius,
                cone.centerX + cone.radius,
                cone.centerY + cone.radius,
            )
            canvas.drawArc(oval, cone.startAngle, cone.sweepAngle, true, conePaint)
            canvas.restore()
        }

        drawPin(canvas, MapDecor.pin(box))
    }

    private fun drawPin(canvas: Canvas, p: MapDecor.Pin) {
        val r = p.headRadius
        val drop = p.tipY - p.headCenterY
        val path = Path().apply {
            moveTo(p.tipX, p.tipY)
            cubicTo(
                p.tipX - r * 0.35f, p.tipY - drop * 0.45f,
                p.headCenterX - r, p.headCenterY + r * 0.55f,
                p.headCenterX - r, p.headCenterY,
            )
            arcTo(
                RectF(p.headCenterX - r, p.headCenterY - r, p.headCenterX + r, p.headCenterY + r),
                180f,
                180f,
                false,
            )
            cubicTo(
                p.headCenterX + r, p.headCenterY + r * 0.55f,
                p.tipX + r * 0.35f, p.tipY - drop * 0.45f,
                p.tipX, p.tipY,
            )
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapDecor.PIN_COLOR_ARGB })
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MapDecor.PIN_STROKE_ARGB
                style = Paint.Style.STROKE
                strokeWidth = p.strokeWidth
            },
        )
        canvas.drawCircle(
            p.headCenterX,
            p.headCenterY,
            p.dotRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapDecor.PIN_DOT_ARGB },
        )
    }

    /** App logo: orange rounded square with a white camera and an orange pin. */
    private fun drawLogo(canvas: Canvas, box: Box) {
        val s = box.width / 48f
        canvas.save()
        canvas.translate(box.left, box.top)
        canvas.scale(s, s)
        val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE65100.toInt() }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(0f, 0f, 48f, 48f), 10f, 10f, orange)
        canvas.drawRoundRect(RectF(9f, 14f, 39f, 38f), 4f, 4f, white)
        canvas.drawRect(RectF(19f, 10.5f, 29f, 15f), white)
        val pin = Path().apply {
            moveTo(24f, 35f)
            cubicTo(22f, 31f, 18.5f, 28.5f, 18.5f, 24.5f)
            arcTo(RectF(18.5f, 19f, 29.5f, 30f), 180f, 180f, false)
            cubicTo(29.5f, 28.5f, 26f, 31f, 24f, 35f)
            close()
        }
        canvas.drawPath(pin, orange)
        canvas.drawCircle(24f, 24.5f, 2f, white)
        canvas.restore()
    }

    /** Halves a large map image until it is at most twice the target size (avoids aliasing). */
    private fun downscaleFor(bitmap: Bitmap, targetPx: Float): Bitmap {
        var b = bitmap
        while (b.width > targetPx * 2 && b.width >= 64) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        return b
    }

    private fun Box.rect() = RectF(left, top, right, bottom)

    companion object {
        const val BRAND = "SiteCam"
    }
}
