package com.lanrex.sitecam.core.jpeg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGHuffmanTable
import javax.imageio.plugins.jpeg.JPEGQTable
import kotlin.math.log10

class JpegEncoderTest {

    @Test
    fun huffmanTablesAreTheStandardOnes() {
        assertSpec(JPEGHuffmanTable.StdDCLuminance, JpegTables.DC_LUMA_BITS, JpegTables.DC_LUMA_VALUES)
        assertSpec(JPEGHuffmanTable.StdDCChrominance, JpegTables.DC_CHROMA_BITS, JpegTables.DC_CHROMA_VALUES)
        assertSpec(JPEGHuffmanTable.StdACLuminance, JpegTables.AC_LUMA_BITS, JpegTables.AC_LUMA_VALUES)
        assertSpec(JPEGHuffmanTable.StdACChrominance, JpegTables.AC_CHROMA_BITS, JpegTables.AC_CHROMA_VALUES)
    }

    @Test
    fun quantTablesAreTheStandardOnes() {
        assertArrayEquals(JPEGQTable.K1Luminance.table, JpegTables.LUMA_QUANT)
        assertArrayEquals(JPEGQTable.K2Chrominance.table, JpegTables.CHROMA_QUANT)
        assertTrue(JpegTables.scaledQuantTable(JpegTables.LUMA_QUANT, 100).all { it == 1 })
        assertArrayEquals(JpegTables.LUMA_QUANT, JpegTables.scaledQuantTable(JpegTables.LUMA_QUANT, 50))
    }

    @Test
    fun quality100RoundTripIsNearlyLossless444() {
        val (w, h) = 333 to 251
        val pixels = testImage(w, h, seed = 1)
        val jpeg = encode(pixels, w, h, ChromaSubsampling.YUV444, rowsPerStrip = 64)
        val decoded = decode(jpeg, w, h)
        val psnr = psnr(pixels, decoded)
        assertTrue("PSNR too low: $psnr", psnr > 44.0)
    }

    @Test
    fun quality100RoundTrip420() {
        // Half-resolution colour loses colour edges by design, so check brightness detail.
        val (w, h) = 400 to 300
        val pixels = testImage(w, h, seed = 2)
        val jpeg = encode(pixels, w, h, ChromaSubsampling.YUV420, rowsPerStrip = 48)
        val decoded = decode(jpeg, w, h)
        val psnr = lumaPsnr(pixels, decoded)
        assertTrue("Luma PSNR too low: $psnr", psnr > 45.0)
    }

    @Test
    fun stripSizeDoesNotChangeTheFile() {
        val (w, h) = 129 to 97
        val pixels = testImage(w, h, seed = 3)
        for (mode in ChromaSubsampling.values()) {
            val oneStrip = encode(pixels, w, h, mode, rowsPerStrip = 4096, threads = 1)
            val manyStrips = encode(pixels, w, h, mode, rowsPerStrip = 16, threads = 3)
            assertArrayEquals(oneStrip, manyStrips)
        }
    }

    @Test
    fun tinyAndOddSizes() {
        for ((w, h) in listOf(1 to 1, 7 to 3, 17 to 33, 8 to 8, 16 to 16, 31 to 1)) {
            for (mode in ChromaSubsampling.values()) {
                val pixels = testImage(w, h, seed = w * 31 + h)
                val decoded = decode(encode(pixels, w, h, mode, rowsPerStrip = 16), w, h)
                assertEquals(w * h, decoded.size)
            }
        }
    }

    @Test
    fun exifSegmentIsWrittenRightAfterSoi() {
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val pixels = testImage(16, 16, seed = 9)
        val out = ByteArrayOutputStream()
        val encoder = JpegEncoder(16, 16, 100, ChromaSubsampling.YUV444)
        JpegStripWriter(encoder, threads = 1).write(out, listOf(app1), { first, count, dest ->
            System.arraycopy(pixels, first * 16, dest, 0, count * 16)
        })
        val bytes = out.toByteArray()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertArrayEquals(app1, bytes.copyOfRange(2, 2 + app1.size))
        assertEquals(0xD9, bytes.last().toInt() and 0xFF)
        decode(bytes, 16, 16)
    }

    @Test
    fun flatColoursComeBackExactly() {
        val (w, h) = 64 to 48
        val colours = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF336699.toInt())
        for (c in colours) {
            val pixels = IntArray(w * h) { c }
            val decoded = decode(encode(pixels, w, h, ChromaSubsampling.YUV444, 16), w, h)
            for (p in decoded) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val diff = ((p shr shift) and 0xFF) - ((c shr shift) and 0xFF)
                    assertTrue("colour drift $diff", kotlin.math.abs(diff) <= 2)
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun assertSpec(table: JPEGHuffmanTable, bits: IntArray, values: IntArray) {
        assertArrayEquals(table.lengths.map { it.toInt() }.toIntArray(), bits)
        assertArrayEquals(table.values.map { it.toInt() }.toIntArray(), values)
    }

    private fun encode(
        pixels: IntArray,
        w: Int,
        h: Int,
        mode: ChromaSubsampling,
        rowsPerStrip: Int,
        threads: Int = 2,
    ): ByteArray {
        val encoder = JpegEncoder(w, h, 100, mode)
        val out = ByteArrayOutputStream()
        JpegStripWriter(encoder, threads, rowsPerStrip).write(out, emptyList(), { first, count, dest ->
            System.arraycopy(pixels, first * w, dest, 0, count * w)
        })
        return out.toByteArray()
    }

    private fun decode(jpeg: ByteArray, w: Int, h: Int): IntArray {
        val img: BufferedImage = ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("ImageIO could not decode")
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        return img.getRGB(0, 0, w, h, null, 0, w)
    }

    /** Gradients, hard edges and noise, like a real photo with a stamp on it. */
    private fun testImage(w: Int, h: Int, seed: Int): IntArray {
        val rnd = Random(seed.toLong())
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var r = (x * 255 / maxOf(1, w - 1))
            var g = (y * 255 / maxOf(1, h - 1))
            var b = ((x + y) * 3) and 0xFF
            if ((x / 9 + y / 13) % 5 == 0) { r = 255 - r; b = 30 }
            r = (r + rnd.nextInt(9) - 4).coerceIn(0, 255)
            g = (g + rnd.nextInt(9) - 4).coerceIn(0, 255)
            b = (b + rnd.nextInt(9) - 4).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun lumaPsnr(a: IntArray, b: IntArray): Double {
        fun luma(p: Int) = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        var sum = 0.0
        for (i in a.indices) {
            val d = luma(a[i]) - luma(b[i])
            sum += d * d
        }
        val mse = sum / a.size
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private fun psnr(a: IntArray, b: IntArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)
                sum += (d * d).toDouble()
            }
        }
        val mse = sum / (a.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }
}
