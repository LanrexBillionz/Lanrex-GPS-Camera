package com.lanrex.sitecam.core.jpeg

import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

/**
 * Feeds an image to [JpegEncoder] strip by strip and writes the JPEG to [out].
 *
 * Pixels are requested in order from [RowSource] on the calling thread (so an
 * Android BitmapRegionDecoder can be used), while strips are compressed on
 * [threads] worker threads. Memory use is bounded by a few strips, whatever
 * the image size.
 */
class JpegStripWriter(
    private val encoder: JpegEncoder,
    private val threads: Int = defaultThreads(),
    rowsPerStrip: Int = defaultRowsPerStrip(encoder),
) {
    /** Rows per strip, rounded to whole MCU rows. */
    val stripRows: Int = run {
        val mcu = encoder.mcuHeight
        maxOf(mcu, (rowsPerStrip / mcu) * mcu)
    }

    fun interface RowSource {
        /** Fill [dest] (stride = image width) with ARGB rows [firstRow, firstRow + rowCount). */
        fun readRows(firstRow: Int, rowCount: Int, dest: IntArray)
    }

    fun write(
        out: OutputStream,
        headerSegments: List<ByteArray>,
        source: RowSource,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        val width = encoder.width
        val height = encoder.height
        out.write(encoder.header(headerSegments))

        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        val freeBuffers = LinkedBlockingQueue<IntArray>()
        var allocated = 0
        val maxBuffers = threads + 2
        val pending = ArrayDeque<Future<ByteArray>>()
        try {
            var row = 0
            while (row < height) {
                if (isCancelled()) throw CancellationException("Stamping cancelled")
                val count = minOf(stripRows, height - row)
                val buffer = freeBuffers.poll() ?: if (allocated < maxBuffers) {
                    allocated++
                    IntArray(width * stripRows)
                } else {
                    freeBuffers.take()
                }
                source.readRows(row, count, buffer)
                val first = row
                pending.addLast(
                    pool.submit(Callable {
                        try {
                            encoder.encodeRows(buffer, 0, width, first, count)
                        } finally {
                            freeBuffers.put(buffer)
                        }
                    }),
                )
                row += count
                while (pending.size > threads) out.write(awaitResult(pending.removeFirst()))
                onProgress(row.toFloat() / height)
            }
            while (pending.isNotEmpty()) out.write(awaitResult(pending.removeFirst()))
            out.write(encoder.trailer())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitResult(future: Future<ByteArray>): ByteArray = try {
        future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** About 8 MB of pixels per strip, at least one MCU row. */
        fun defaultRowsPerStrip(encoder: JpegEncoder): Int =
            (2_000_000 / encoder.width).coerceIn(encoder.mcuHeight, 256)
    }
}
