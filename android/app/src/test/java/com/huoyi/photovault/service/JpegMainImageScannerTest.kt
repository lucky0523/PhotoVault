package com.huoyi.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [JpegMainImageScanner]: locating the *main* image's EOI by walking JPEG marker
 * segments (so an Exif thumbnail's `FF D9` inside APP1 never counts). Synthetic bytes only.
 */
class JpegMainImageScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // a. JFIF + tables + SOS + entropy + EOI → Found at the EOI.
    @Test
    fun jfifJpeg_foundAtEoi() {
        val body = soi + app0Jfif() + tables() + sos() + entropy(2_000)
        assertScan(JpegEoiScan.Found(body.size.toLong()), body + eoi)
    }

    // b. Exif APP1 with an embedded thumbnail → Found at the main EOI, not the thumbnail's.
    @Test
    fun exifThumbnail_foundAtMainEoi() {
        val body = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(5_000)
        assertScan(JpegEoiScan.Found(body.size.toLong()), body + eoi)
    }

    // c. Only the thumbnail has an EOI → NotFound.
    @Test
    fun exifThumbnailOnly_notFound() {
        val bytes = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(5_000)
        assertScan(JpegEoiScan.NotFound, bytes)
    }

    // d. FF 00 stuffing, RST0..RST7 and fill bytes before the EOI.
    @Test
    fun stuffingRstAndFillBytes_foundAtLastFfBeforeD9() {
        var data = entropy(100) + byteArrayOf(0xFF.toByte(), 0x00) + entropy(50)
        for (rst in 0xD0..0xD7) data += byteArrayOf(0xFF.toByte(), rst.toByte()) + entropy(10)
        val bytes = soi + app0Jfif() + tables() + sos() + data +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertScan(JpegEoiScan.Found(bytes.size - 2L), bytes)
    }

    // e. Progressive: DHT + second SOS between scans.
    @Test
    fun progressiveMultiScan_foundAfterSecondScan() {
        val dht = byteArrayOf(0xFF.toByte(), 0xC4.toByte(), 0x00, 0x05, 0x00, 0x01, 0x02)
        val body = soi + app0Jfif() + tables() + sos() + entropy(500) + dht + sos() + entropy(500)
        assertScan(JpegEoiScan.Found(body.size.toLong()), body + eoi)
    }

    // f. The EOI's FF is the last byte of the first 64KB buffer, D9 the first of the next.
    @Test
    fun eoiSplitAcrossBufferBoundary_found() {
        val prefix = soi + app0Jfif() + tables() + sos()
        val body = prefix + entropy(65_535 - prefix.size)
        assertEquals(65_535, body.size)
        assertScan(JpegEoiScan.Found(65_535L), body + eoi + entropy(100))
    }

    // g. EOI at/after scanEnd is not seen.
    @Test
    fun eoiBeyondScanEnd_notFound() {
        val body = soi + app0Jfif() + tables() + sos() + entropy(2_000)
        val file = write(body + eoi)
        assertEquals(JpegEoiScan.NotFound, JpegMainImageScanner.findMainImageEoi(file, body.size + 1L))
    }

    // h. A segment whose declared length runs past the data → NotFound.
    @Test
    fun segmentOverrunsData_notFound() {
        val bytes = soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(), 0xFF.toByte()) + entropy(1_000)
        assertScan(JpegEoiScan.NotFound, bytes)
    }

    // i. Degenerate inputs.
    @Test
    fun soiOnly_notFound() = assertScan(JpegEoiScan.NotFound, soi)

    @Test
    fun ffThenZeroAfterSoi_inconclusive() =
        assertScan(JpegEoiScan.Inconclusive, soi + byteArrayOf(0xFF.toByte(), 0x00) + entropy(100))

    @Test
    fun nonMarkerAfterSoi_inconclusive() =
        assertScan(JpegEoiScan.Inconclusive, soi + byteArrayOf(0x01, 0x02) + entropy(100))

    @Test
    fun pngSignature_inconclusive() = assertScan(
        JpegEoiScan.Inconclusive,
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + entropy(100)
    )

    @Test
    fun emptyFile_inconclusive() = assertScan(JpegEoiScan.Inconclusive, ByteArray(0))

    // j. MPF-like: main image, a second full JPEG, then a non-zero trailer → first image's EOI.
    @Test
    fun mpfSecondImageAndTrailer_foundAtFirstEoi() {
        val main = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(3_000)
        val second = soi + tables() + sos() + entropy(2_000) + eoi
        assertScan(JpegEoiScan.Found(main.size.toLong()), main + eoi + second + entropy(4_096))
    }

    // --- helpers -------------------------------------------------------------

    private fun assertScan(expected: JpegEoiScan, bytes: ByteArray) {
        assertEquals(expected, JpegMainImageScanner.findMainImageEoi(write(bytes)))
    }

    private fun write(bytes: ByteArray): File =
        File.createTempFile("jpeg", ".bin", tempFolder.root).apply { writeBytes(bytes) }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun app0Jfif(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) +
            "JFIF\u0000".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)

    /** APP1 Exif segment that embeds a complete thumbnail JPEG (`FF D8 … FF D9`). */
    private fun app1ExifWithThumbnail(): ByteArray {
        val thumbnail = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 0x01, 0x02,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02
        ) + entropy(200) + eoi
        val content = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + entropy(64) + thumbnail + entropy(32)
        val len = content.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len ushr 8).toByte(), (len and 0xFF).toByte()) + content
    }

    /** DQT + SOF0 + DHT (contents are not validated by the scanner). */
    private fun tables(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00) + ByteArray(64) { (it + 1).toByte() } +
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00
            ) +
            byteArrayOf(0xFF.toByte(), 0xC4.toByte(), 0x00, 0x05, 0x00, 0x01, 0x02)

    private fun sos(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00)

    /** Entropy-like bytes in 1..0xFE: never 0x00 or 0xFF. */
    private fun entropy(n: Int): ByteArray = ByteArray(n) { ((it * 37 + 11) % 0xFE + 1).toByte() }
}
