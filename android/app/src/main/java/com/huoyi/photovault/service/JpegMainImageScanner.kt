package com.huoyi.photovault.service

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Outcome of searching a JPEG stream for the main (primary) image's EOI marker. */
internal sealed interface JpegEoiScan {
    /** The main image's EOI was found; [eoiOffset] is the offset of the `FF` in its `FF D9`. */
    data class Found(val eoiOffset: Long) : JpegEoiScan

    /** The marker stream is well-formed but the scan end was reached before the main EOI. */
    object NotFound : JpegEoiScan

    /** Not a parseable JPEG marker stream (or the file could not be read): no evidence. */
    object Inconclusive : JpegEoiScan
}

/**
 * Pure, Android-independent locator for the EOI (`FF D9`) of a JPEG's *main* image.
 *
 * Exif JPEGs carry a complete thumbnail JPEG (with its own `FF D8 … FF D9`) inside the APP1
 * segment, so a plain byte search for `FF D9` can stop at the thumbnail. This scanner instead
 * walks the marker segments from offset 2 (right after the SOI), skipping each header segment
 * (APPn, DQT, SOFn, DHT, COM, …) by its 2-byte big-endian length, so the bytes inside APP1 are
 * never inspected. After the first SOS (`FF DA`) it scans the entropy-coded data, where:
 *  - `FF 00` is byte stuffing and `FF D0`..`FF D7` are restart markers (not EOI);
 *  - extra `FF` fill bytes before a marker are skipped;
 *  - table/scan segments between progressive scans (DHT, DQT, SOS, DRI, DNL, DAC, COM, APPn)
 *    are skipped by their length;
 *  - the first `FF D9` is the main image's EOI.
 *
 * Reads are streaming and bounded: a single reusable 64KB buffer, segment payloads are skipped
 * by seeking (not read), and no byte at or after `scanEnd` is ever read.
 */
internal object JpegMainImageScanner {

    /** Reusable read buffer size for the streaming scan. */
    private const val SCAN_BUFFER_SIZE = 64 * 1024

    /**
     * Find the main image's EOI in [file], considering only bytes in `[0, scanEnd)`.
     *
     * @return [JpegEoiScan.Found] with the EOI offset, [JpegEoiScan.NotFound] if the stream is a
     *         valid marker stream cut off before the main EOI, or [JpegEoiScan.Inconclusive] if
     *         the bytes are not a parseable JPEG stream or cannot be read.
     */
    fun findMainImageEoi(file: File, scanEnd: Long = file.length()): JpegEoiScan {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val end = minOf(scanEnd, raf.length())
                val reader = BoundedReader(raf, end)
                if (reader.read() != 0xFF || reader.read() != 0xD8) {
                    return JpegEoiScan.Inconclusive
                }
                scan(reader)
            }
        } catch (e: IOException) {
            JpegEoiScan.Inconclusive
        }
    }

    private fun scan(reader: BoundedReader): JpegEoiScan {
        // Header phase: walk marker segments up to and including the first SOS.
        while (true) {
            val prefix = reader.read()
            if (prefix < 0) return JpegEoiScan.NotFound
            if (prefix != 0xFF) return JpegEoiScan.Inconclusive
            val marker = readMarker(reader)
            if (marker < 0) return JpegEoiScan.NotFound
            when (marker) {
                0x00, 0xD8, 0xD9 -> return JpegEoiScan.Inconclusive
                0x01, in 0xD0..0xD7 -> continue // standalone markers, no length
                else -> {
                    skipSegment(reader)?.let { return it }
                    if (marker == 0xDA) break // SOS: entropy-coded data follows
                }
            }
        }

        // Entropy phase: scan coded data for the main image's EOI.
        while (true) {
            val b = reader.read()
            if (b < 0) return JpegEoiScan.NotFound
            if (b != 0xFF) continue
            val marker = readMarker(reader)
            if (marker < 0) return JpegEoiScan.NotFound
            when (marker) {
                0x00, in 0xD0..0xD7 -> continue // byte stuffing / RSTn
                0xD9 -> return JpegEoiScan.Found(reader.position - 2)
                // Inter-scan segments of progressive / multi-scan JPEGs.
                0xC4, 0xCC, 0xDA, 0xDB, 0xDC, 0xDD, 0xFE, in 0xE0..0xEF ->
                    skipSegment(reader)?.let { return it }
                else -> return JpegEoiScan.Inconclusive // incl. D8: a new SOI before main EOI
            }
        }
    }

    /** Read a marker code after an `FF` prefix, skipping `FF` fill bytes. -1 at end. */
    private fun readMarker(reader: BoundedReader): Int {
        var marker = reader.read()
        while (marker == 0xFF) marker = reader.read()
        return marker
    }

    /**
     * Skip a length-prefixed segment whose marker was just read. Returns null on success, or the
     * terminal scan result: [JpegEoiScan.NotFound] if the segment runs past the scan end,
     * [JpegEoiScan.Inconclusive] if its declared length is invalid.
     */
    private fun skipSegment(reader: BoundedReader): JpegEoiScan? {
        val len = reader.readU16()
        if (len < 0) return JpegEoiScan.NotFound
        if (len < 2) return JpegEoiScan.Inconclusive
        val payload = (len - 2).toLong()
        if (reader.position + payload > reader.end) return JpegEoiScan.NotFound
        reader.skip(payload)
        return null
    }

    /**
     * Forward-only reader over `[0, end)` of a [RandomAccessFile] with one reusable buffer.
     * [skip] only advances the position; skipped bytes outside the buffer are never read.
     */
    private class BoundedReader(private val raf: RandomAccessFile, val end: Long) {
        private val buffer = ByteArray(SCAN_BUFFER_SIZE)
        private var bufferStart = 0L
        private var bufferLength = 0

        var position = 0L
            private set

        /** Next unsigned byte, or -1 at [end]. */
        fun read(): Int {
            if (position >= end) return -1
            if (position < bufferStart || position >= bufferStart + bufferLength) fill()
            val value = buffer[(position - bufferStart).toInt()].toInt() and 0xFF
            position++
            return value
        }

        /** Next big-endian unsigned 16-bit value, or -1 if [end] is reached. */
        fun readU16(): Int {
            val hi = read()
            if (hi < 0) return -1
            val lo = read()
            if (lo < 0) return -1
            return (hi shl 8) or lo
        }

        /** Advance by [n] bytes without reading them; caller ensures `position + n <= end`. */
        fun skip(n: Long) {
            position += n
        }

        private fun fill() {
            val toRead = minOf(SCAN_BUFFER_SIZE.toLong(), end - position).toInt()
            raf.seek(position)
            raf.readFully(buffer, 0, toRead)
            bufferStart = position
            bufferLength = toRead
        }
    }
}
