package com.lanrex.sitecam.core.jpeg

import java.io.ByteArrayOutputStream

enum class ChromaSubsampling(val h: Int, val v: Int) {
    /** Full colour resolution (best quality, larger files). */
    YUV444(1, 1),

    /** Colour at half resolution in both directions, like camera JPEGs. */
    YUV420(2, 2),
}

/**
 * Baseline JPEG encoder that works on horizontal strips of rows.
 *
 * Each row of MCUs is written as its own restart interval, so separate strips
 * can be encoded independently (and in parallel) and then concatenated:
 * header() + encodeRows(strip 1) + encodeRows(strip 2) + ... + trailer().
 * This lets SiteCam stamp 200 MP photos without ever holding the whole image
 * in memory, and without resizing it.
 *
 * Uses the standard Huffman tables and libjpeg's quality scaling
 * (quality 100 = every quantisation step is 1).
 */
class JpegEncoder(
    val width: Int,
    val height: Int,
    val quality: Int = 100,
    val subsampling: ChromaSubsampling = ChromaSubsampling.YUV444,
) {
    init {
        require(width in 1..65535 && height in 1..65535) { "JPEG size must be 1..65535, got $width x $height" }
        require(quality in 1..100) { "quality must be 1..100" }
    }

    val mcuWidth = 8 * subsampling.h
    val mcuHeight = 8 * subsampling.v
    val mcusPerRow = (width + mcuWidth - 1) / mcuWidth
    val mcuRowCount = (height + mcuHeight - 1) / mcuHeight

    private val lumaQuant = JpegTables.scaledQuantTable(JpegTables.LUMA_QUANT, quality)
    private val chromaQuant = JpegTables.scaledQuantTable(JpegTables.CHROMA_QUANT, quality)
    private val lumaDivisors = divisors(lumaQuant)
    private val chromaDivisors = divisors(chromaQuant)
    private val dcLuma = HuffmanCodes(JpegTables.DC_LUMA_BITS, JpegTables.DC_LUMA_VALUES)
    private val acLuma = HuffmanCodes(JpegTables.AC_LUMA_BITS, JpegTables.AC_LUMA_VALUES)
    private val dcChroma = HuffmanCodes(JpegTables.DC_CHROMA_BITS, JpegTables.DC_CHROMA_VALUES)
    private val acChroma = HuffmanCodes(JpegTables.AC_CHROMA_BITS, JpegTables.AC_CHROMA_VALUES)

    /**
     * Everything from SOI up to and including the SOS header.
     * [segments] are complete marker segments (e.g. an APP1 Exif block) written right after SOI.
     */
    fun header(segments: List<ByteArray> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream(1024)
        out.write(0xFF); out.write(0xD8) // SOI
        segments.forEach { out.write(it) }

        // DQT: both tables in one segment, zig-zag order.
        out.write(0xFF); out.write(0xDB)
        out.writeShort(2 + 2 * 65)
        out.write(0x00)
        for (k in 0 until 64) out.write(lumaQuant[JpegTables.ZIGZAG[k]])
        out.write(0x01)
        for (k in 0 until 64) out.write(chromaQuant[JpegTables.ZIGZAG[k]])

        // SOF0: baseline, 8-bit, 3 components.
        out.write(0xFF); out.write(0xC0)
        out.writeShort(8 + 3 * 3)
        out.write(8)
        out.writeShort(height)
        out.writeShort(width)
        out.write(3)
        out.write(1); out.write((subsampling.h shl 4) or subsampling.v); out.write(0)
        out.write(2); out.write(0x11); out.write(1)
        out.write(3); out.write(0x11); out.write(1)

        // DHT: all four tables.
        val tables = listOf(0x00 to dcLuma, 0x10 to acLuma, 0x01 to dcChroma, 0x11 to acChroma)
        out.write(0xFF); out.write(0xC4)
        out.writeShort(2 + tables.sumOf { 17 + it.second.values.size })
        for ((id, t) in tables) {
            out.write(id)
            t.bits.forEach { out.write(it) }
            t.values.forEach { out.write(it) }
        }

        // DRI: one restart interval per MCU row.
        if (mcuRowCount > 1) {
            out.write(0xFF); out.write(0xDD)
            out.writeShort(4)
            out.writeShort(mcusPerRow)
        }

        // SOS
        out.write(0xFF); out.write(0xDA)
        out.writeShort(6 + 2 * 3)
        out.write(3)
        out.write(1); out.write(0x00)
        out.write(2); out.write(0x11)
        out.write(3); out.write(0x11)
        out.write(0); out.write(63); out.write(0)
        return out.toByteArray()
    }

    fun trailer(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * Encodes image rows [firstRow, firstRow + rowCount) from ARGB pixels
     * ([pixels] starting at [offset], [stride] ints per row; only RGB is used).
     *
     * [firstRow] must be a multiple of [mcuHeight], and [rowCount] too unless
     * the strip reaches the bottom of the image. Safe to call from several
     * threads at once.
     */
    fun encodeRows(pixels: IntArray, offset: Int, stride: Int, firstRow: Int, rowCount: Int): ByteArray {
        require(firstRow % mcuHeight == 0) { "firstRow $firstRow is not a multiple of $mcuHeight" }
        require(rowCount > 0 && firstRow + rowCount <= height) { "rows out of range" }
        val endRow = firstRow + rowCount
        require(rowCount % mcuHeight == 0 || endRow == height) { "rowCount must be a multiple of $mcuHeight" }

        val writer = BitWriter(maxOf(4096, width * rowCount))
        val s = Scratch(mcuWidth, mcuHeight)
        val firstMcuRow = firstRow / mcuHeight
        val endMcuRow = (endRow + mcuHeight - 1) / mcuHeight

        for (mcuRow in firstMcuRow until endMcuRow) {
            var predY = 0
            var predCb = 0
            var predCr = 0
            val y0 = mcuRow * mcuHeight
            for (mcuX in 0 until mcusPerRow) {
                loadMcu(pixels, offset, stride, firstRow, endRow, mcuX * mcuWidth, y0, s)
                if (subsampling == ChromaSubsampling.YUV444) {
                    predY = encodeBlock(s.y, 0, 8, lumaDivisors, dcLuma, acLuma, predY, s, writer)
                    predCb = encodeBlock(s.cb, 0, 8, chromaDivisors, dcChroma, acChroma, predCb, s, writer)
                    predCr = encodeBlock(s.cr, 0, 8, chromaDivisors, dcChroma, acChroma, predCr, s, writer)
                } else {
                    predY = encodeBlock(s.y, 0, 16, lumaDivisors, dcLuma, acLuma, predY, s, writer)
                    predY = encodeBlock(s.y, 8, 16, lumaDivisors, dcLuma, acLuma, predY, s, writer)
                    predY = encodeBlock(s.y, 128, 16, lumaDivisors, dcLuma, acLuma, predY, s, writer)
                    predY = encodeBlock(s.y, 136, 16, lumaDivisors, dcLuma, acLuma, predY, s, writer)
                    downsample(s.cb, s.sub)
                    predCb = encodeBlock(s.sub, 0, 8, chromaDivisors, dcChroma, acChroma, predCb, s, writer)
                    downsample(s.cr, s.sub)
                    predCr = encodeBlock(s.sub, 0, 8, chromaDivisors, dcChroma, acChroma, predCr, s, writer)
                }
            }
            writer.padToByte()
            if (mcuRow < mcuRowCount - 1) writer.marker(0xD0 + (mcuRow and 7))
        }
        return writer.toByteArray()
    }

    /** Converts one MCU of RGB to level-shifted Y, Cb, Cr planes, repeating edge pixels. */
    private fun loadMcu(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        firstRow: Int,
        endRow: Int,
        x0: Int,
        y0: Int,
        s: Scratch,
    ) {
        val w = s.mcuW
        var i = 0
        for (dy in 0 until s.mcuH) {
            val y = minOf(y0 + dy, endRow - 1)
            val rowStart = offset + (y - firstRow) * stride
            for (dx in 0 until w) {
                val x = minOf(x0 + dx, width - 1)
                val p = pixels[rowStart + x]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                s.y[i] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                s.cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
                s.cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
                i++
            }
        }
    }

    /** 16x16 plane -> 8x8 block by averaging each 2x2 group. */
    private fun downsample(plane: FloatArray, out: FloatArray) {
        var o = 0
        for (by in 0 until 8) {
            val r0 = by * 32
            for (bx in 0 until 8) {
                val p = r0 + bx * 2
                out[o++] = (plane[p] + plane[p + 1] + plane[p + 16] + plane[p + 17]) * 0.25f
            }
        }
    }

    /** DCT + quantisation + Huffman coding of one 8x8 block. Returns the new DC predictor. */
    private fun encodeBlock(
        plane: FloatArray,
        start: Int,
        planeStride: Int,
        divisors: FloatArray,
        dc: HuffmanCodes,
        ac: HuffmanCodes,
        predictor: Int,
        s: Scratch,
        w: BitWriter,
    ): Int {
        val block = s.block
        for (r in 0 until 8) {
            System.arraycopy(plane, start + r * planeStride, block, r * 8, 8)
        }
        forwardDct(block)
        val coef = s.coef
        for (i in 0 until 64) {
            val v = block[i] * divisors[i]
            coef[i] = if (v >= 0f) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())
        }

        val dcValue = coef[0].coerceIn(-1024, 1023)
        val diff = dcValue - predictor
        if (diff == 0) {
            w.write(dc.code[0], dc.size[0])
        } else {
            val n = bitLength(if (diff < 0) -diff else diff)
            w.write(dc.code[n], dc.size[n])
            w.write(if (diff < 0) diff - 1 else diff, n)
        }

        var run = 0
        for (k in 1 until 64) {
            val v = coef[JpegTables.ZIGZAG[k]].coerceIn(-1023, 1023)
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                w.write(ac.code[0xF0], ac.size[0xF0])
                run -= 16
            }
            val n = bitLength(if (v < 0) -v else v)
            val symbol = (run shl 4) or n
            w.write(ac.code[symbol], ac.size[symbol])
            w.write(if (v < 0) v - 1 else v, n)
            run = 0
        }
        if (run > 0) w.write(ac.code[0x00], ac.size[0x00])
        return dcValue
    }

    private class Scratch(val mcuW: Int, val mcuH: Int) {
        val y = FloatArray(mcuW * mcuH)
        val cb = FloatArray(mcuW * mcuH)
        val cr = FloatArray(mcuW * mcuH)
        val sub = FloatArray(64)
        val block = FloatArray(64)
        val coef = IntArray(64)
    }

    /** Collects entropy-coded bytes with 0xFF byte stuffing. */
    private class BitWriter(initialCapacity: Int) {
        private var buffer = ByteArray(initialCapacity)
        private var size = 0
        private var acc = 0L
        private var count = 0

        fun write(bits: Int, length: Int) {
            if (length == 0) return
            acc = (acc shl length) or (bits.toLong() and ((1L shl length) - 1))
            count += length
            while (count >= 8) {
                count -= 8
                val b = ((acc ushr count) and 0xFF).toInt()
                put(b)
                if (b == 0xFF) put(0)
            }
        }

        /** Pads the last partial byte with 1-bits, as the standard requires before a marker. */
        fun padToByte() {
            if (count > 0) write(0x7F, 7)
            acc = 0
            count = 0
        }

        fun marker(code: Int) {
            put(0xFF)
            put(code)
        }

        private fun put(b: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = b.toByte()
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    companion object {
        private val AAN_SCALE = doubleArrayOf(
            1.0, 1.387039845, 1.306562965, 1.175875602,
            1.0, 0.785694958, 0.541196100, 0.275899379,
        )

        /** Folds the AAN DCT output scaling into the quantisation step, like libjpeg's float path. */
        private fun divisors(quant: IntArray): FloatArray = FloatArray(64) { i ->
            val row = i / 8
            val col = i % 8
            (1.0 / (quant[i] * AAN_SCALE[row] * AAN_SCALE[col] * 8.0)).toFloat()
        }

        private fun bitLength(value: Int): Int = 32 - Integer.numberOfLeadingZeros(value)

        private fun ByteArrayOutputStream.writeShort(v: Int) {
            write((v ushr 8) and 0xFF)
            write(v and 0xFF)
        }

        /** Arai-Agui-Nakajima floating-point forward DCT (as in libjpeg's jfdctflt.c), in place. */
        internal fun forwardDct(d: FloatArray) {
            for (row in 0 until 8) {
                val o = row * 8
                val tmp0 = d[o] + d[o + 7]
                val tmp7 = d[o] - d[o + 7]
                val tmp1 = d[o + 1] + d[o + 6]
                val tmp6 = d[o + 1] - d[o + 6]
                val tmp2 = d[o + 2] + d[o + 5]
                val tmp5 = d[o + 2] - d[o + 5]
                val tmp3 = d[o + 3] + d[o + 4]
                val tmp4 = d[o + 3] - d[o + 4]

                var tmp10 = tmp0 + tmp3
                val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2
                var tmp12 = tmp1 - tmp2

                d[o] = tmp10 + tmp11
                d[o + 4] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[o + 2] = tmp13 + z1
                d[o + 6] = tmp13 - z1

                tmp10 = tmp4 + tmp5
                tmp11 = tmp5 + tmp6
                tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3
                val z13 = tmp7 - z3
                d[o + 5] = z13 + z2
                d[o + 3] = z13 - z2
                d[o + 1] = z11 + z4
                d[o + 7] = z11 - z4
            }
            for (col in 0 until 8) {
                val tmp0 = d[col] + d[col + 56]
                val tmp7 = d[col] - d[col + 56]
                val tmp1 = d[col + 8] + d[col + 48]
                val tmp6 = d[col + 8] - d[col + 48]
                val tmp2 = d[col + 16] + d[col + 40]
                val tmp5 = d[col + 16] - d[col + 40]
                val tmp3 = d[col + 24] + d[col + 32]
                val tmp4 = d[col + 24] - d[col + 32]

                var tmp10 = tmp0 + tmp3
                val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2
                var tmp12 = tmp1 - tmp2

                d[col] = tmp10 + tmp11
                d[col + 32] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[col + 16] = tmp13 + z1
                d[col + 48] = tmp13 - z1

                tmp10 = tmp4 + tmp5
                tmp11 = tmp5 + tmp6
                tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3
                val z13 = tmp7 - z3
                d[col + 40] = z13 + z2
                d[col + 24] = z13 - z2
                d[col + 8] = z11 + z4
                d[col + 56] = z11 - z4
            }
        }
    }
}
