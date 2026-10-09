package com.lanrex.sitecam.core.stamp

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in image pixels (display orientation). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Box) = Box(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom),
    )

    fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/** The text lines of a stamp. A null or blank line is not drawn. */
data class StampLines(
    val title: String? = null,
    val address: String? = null,
    val coordinates: String? = null,
    val time: String? = null,
    val note: String? = null,
)

/** Measures text in the stamp font (Roboto). Implemented with Paint on Android. */
interface TextMeasurer {
    fun width(text: String, sizePx: Float, bold: Boolean): Float

    /** Height of capital letters above the baseline (positive). */
    fun capHeight(sizePx: Float, bold: Boolean): Float

    /** Depth of descenders below the baseline (positive). */
    fun descent(sizePx: Float, bold: Boolean): Float
}

data class PlacedText(
    val text: String,
    val x: Float,
    val baseline: Float,
    val sizePx: Float,
    val bold: Boolean,
)

/** Everything needed to draw one stamp, in display-orientation image pixels. */
data class StampGeometry(
    val imageWidth: Int,
    val imageHeight: Int,
    val shortSide: Float,
    val box: Box,
    val boxRadius: Float,
    val map: Box?,
    val mapRadius: Float,
    val tab: Box,
    val tabRadius: Float,
    val tabIcon: Box,
    val tabLabel: PlacedText,
    val lines: List<PlacedText>,
    /** Union of box, map and tab. */
    val bounds: Box,
    val bottomMargin: Float,
)

/**
 * Lays out the stamp: a strip centred near the bottom of the image with the map
 * thumbnail on the left and the dark text box on the right, plus a small
 * "SiteCam" tab on the top-right edge of the box.
 *
 * All sizes are fractions of the image's SHORTER side, so a portrait and a
 * landscape photo get an identical stamp, at any resolution.
 */
object StampLayout {
    const val STRIP_WIDTH = 0.90f
    const val BOX_HEIGHT = 0.22f
    const val GAP = 0.02f
    const val BOTTOM_MARGIN = 0.02f
    const val TITLE_SIZE = 0.045f
    const val BODY_SIZE = 0.030f
    const val TAB_HEIGHT = 0.040f
    const val CORNER_RADIUS = 0.012f
    const val TAB_CORNER_RADIUS = 0.008f
    const val TAB_GAP = 0.004f
    const val TEXT_PADDING_X = 0.022f
    const val TEXT_PADDING_Y = 0.014f

    /** Baseline-to-baseline distance as a multiple of the next line's font size. */
    const val LINE_ADVANCE = 1.15f

    /** Text never shrinks below this fraction of its normal size as a whole block. */
    const val MIN_SCALE = 0.45f

    /** Background of box and tab: black at 60% opacity. */
    const val BOX_COLOR_ARGB = 0x99000000.toInt()

    fun compute(
        imageWidth: Int,
        imageHeight: Int,
        lines: StampLines,
        showMap: Boolean,
        brand: String,
        measurer: TextMeasurer,
    ): StampGeometry {
        require(imageWidth > 0 && imageHeight > 0) { "Bad image size $imageWidth x $imageHeight" }
        val s = min(imageWidth, imageHeight).toFloat()
        val stripWidth = STRIP_WIDTH * s
        val boxHeight = BOX_HEIGHT * s
        val gap = GAP * s
        val margin = BOTTOM_MARGIN * s
        val stripLeft = (imageWidth - stripWidth) / 2f
        val boxBottom = imageHeight - margin
        val boxTop = boxBottom - boxHeight

        val map = if (showMap) Box(stripLeft, boxTop, stripLeft + boxHeight, boxBottom) else null
        val boxLeft = if (map != null) map.right + gap else stripLeft
        val box = Box(boxLeft, boxTop, stripLeft + stripWidth, boxBottom)

        // Tab: app icon + brand name, right-aligned just above the box.
        val tabHeight = TAB_HEIGHT * s
        val iconSize = tabHeight * 0.72f
        val labelSize = tabHeight * 0.50f
        val tabPadX = tabHeight * 0.28f
        val iconGap = tabHeight * 0.22f
        val labelWidth = measurer.width(brand, labelSize, bold = false)
        val tabWidth = tabPadX + iconSize + iconGap + labelWidth + tabPadX
        val tabBottom = boxTop - TAB_GAP * s
        val tab = Box(box.right - tabWidth, tabBottom - tabHeight, box.right, tabBottom)
        val iconLeft = tab.left + tabPadX
        val tabIcon = Box(iconLeft, tab.centerY - iconSize / 2f, iconLeft + iconSize, tab.centerY + iconSize / 2f)
        val tabLabel = PlacedText(
            text = brand,
            x = tabIcon.right + iconGap,
            baseline = tab.centerY + measurer.capHeight(labelSize, false) / 2f,
            sizePx = labelSize,
            bold = false,
        )

        // Text lines, shrunk or wrapped to fit inside the box.
        val padX = TEXT_PADDING_X * s
        val padY = TEXT_PADDING_Y * s
        val availableWidth = box.width - 2 * padX
        val availableHeight = box.height - 2 * padY
        val fitted = fitLines(lines, s, availableWidth, availableHeight, measurer)
        val placed = placeLines(fitted, box, box.left + padX, measurer)

        var bounds = box.union(tab)
        if (map != null) bounds = bounds.union(map)

        return StampGeometry(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            shortSide = s,
            box = box,
            boxRadius = CORNER_RADIUS * s,
            map = map,
            mapRadius = CORNER_RADIUS * s,
            tab = tab,
            tabRadius = TAB_CORNER_RADIUS * s,
            tabIcon = tabIcon,
            tabLabel = tabLabel,
            lines = placed,
            bounds = bounds,
            bottomMargin = margin,
        )
    }

    /** One wrapped/shrunk physical line before it gets a position. */
    data class FittedLine(val text: String, val sizePx: Float, val bold: Boolean)

    fun fitLines(
        lines: StampLines,
        shortSide: Float,
        availableWidth: Float,
        availableHeight: Float,
        measurer: TextMeasurer,
    ): List<FittedLine> {
        var scale = 1f
        while (true) {
            val titleSize = shortSide * TITLE_SIZE * scale
            val bodySize = shortSide * BODY_SIZE * scale
            val out = ArrayList<FittedLine>()
            lines.title.cleanLine()?.let { out += fitOne(it, titleSize, true, 2, availableWidth, measurer) }
            lines.address.cleanLine()?.let { out += fitOne(it, bodySize, false, 3, availableWidth, measurer) }
            lines.coordinates.cleanLine()?.let { out += fitOne(it, bodySize, false, 1, availableWidth, measurer) }
            lines.time.cleanLine()?.let { out += fitOne(it, bodySize, false, 1, availableWidth, measurer) }
            lines.note.cleanLine()?.let { out += fitOne(it, bodySize, false, 2, availableWidth, measurer) }
            if (blockHeight(out, measurer) <= availableHeight || scale <= MIN_SCALE) return out
            scale = max(MIN_SCALE, scale * 0.94f)
        }
    }

    /** Height from the top of the first line's capitals to the bottom of the last line's descenders. */
    fun blockHeight(lines: List<FittedLine>, measurer: TextMeasurer): Float {
        if (lines.isEmpty()) return 0f
        var h = measurer.capHeight(lines[0].sizePx, lines[0].bold)
        for (i in 1 until lines.size) h += advance(lines[i - 1], lines[i])
        val last = lines.last()
        return h + measurer.descent(last.sizePx, last.bold)
    }

    private fun advance(previous: FittedLine, next: FittedLine): Float =
        next.sizePx * LINE_ADVANCE + max(0f, previous.sizePx - next.sizePx) * 0.25f

    private fun placeLines(lines: List<FittedLine>, box: Box, x: Float, measurer: TextMeasurer): List<PlacedText> {
        if (lines.isEmpty()) return emptyList()
        val height = blockHeight(lines, measurer)
        val top = box.centerY - height / 2f
        var baseline = top + measurer.capHeight(lines[0].sizePx, lines[0].bold)
        val out = ArrayList<PlacedText>(lines.size)
        lines.forEachIndexed { i, line ->
            if (i > 0) baseline += advance(lines[i - 1], line)
            out += PlacedText(line.text, x, baseline, line.sizePx, line.bold)
        }
        return out
    }

    /**
     * Fits one logical line: kept as is when it fits, shrunk a little when it
     * is slightly too long, otherwise wrapped at spaces onto up to [maxLines]
     * lines (shrinking further if needed). Never wider than [width].
     */
    fun fitOne(
        text: String,
        size: Float,
        bold: Boolean,
        maxLines: Int,
        width: Float,
        measurer: TextMeasurer,
    ): List<FittedLine> {
        val w = measurer.width(text, size, bold)
        if (w <= width) return listOf(FittedLine(text, size, bold))
        val shrink = width / w
        if (maxLines <= 1 || shrink >= 0.85f) {
            return listOf(FittedLine(text, size * shrink * 0.999f, bold))
        }
        var s = size
        repeat(24) {
            val wrapped = wrap(text, s, bold, width, measurer)
            if (wrapped.size <= maxLines) return wrapped.map { FittedLine(it, s, bold) }
            s *= 0.94f
        }
        // Pathological input (one enormous word): let every piece fit on its own.
        return wrap(text, s, bold, width, measurer).take(maxLines).map { piece ->
            val pw = measurer.width(piece, s, bold)
            FittedLine(piece, if (pw > width) s * width / pw * 0.999f else s, bold)
        }
    }

    /** Greedy word wrap; words longer than the width are split between characters. */
    fun wrap(text: String, size: Float, bold: Boolean, width: Float, measurer: TextMeasurer): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measurer.width(candidate, size, bold) <= width) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) {
                lines += current
                current = ""
            }
            if (measurer.width(word, size, bold) <= width) {
                current = word
            } else {
                // Split a very long word at character (grapheme-ish) boundaries.
                val pieces = splitLongWord(word, size, bold, width, measurer)
                lines += pieces.dropLast(1)
                current = pieces.last()
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun splitLongWord(word: String, size: Float, bold: Boolean, width: Float, measurer: TextMeasurer): List<String> {
        val units = graphemeUnits(word)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (u in units) {
            if (sb.isNotEmpty() && measurer.width(sb.toString() + u, size, bold) > width) {
                out += sb.toString()
                sb.setLength(0)
            }
            sb.append(u)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Code points, keeping surrogate pairs and flag (regional indicator) pairs together. */
    private fun graphemeUnits(text: String): List<String> {
        val units = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            var end = i + Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && end < text.length) {
                val next = text.codePointAt(end)
                if (next in 0x1F1E6..0x1F1FF) end += Character.charCount(next)
            }
            units += text.substring(i, end)
            i = end
        }
        return units
    }

    private fun String?.cleanLine(): String? =
        this?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}
