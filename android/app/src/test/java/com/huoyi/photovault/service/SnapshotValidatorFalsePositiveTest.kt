package com.huoyi.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for two [SnapshotValidator] false positives seen on a real device:
 *  - JPEG content saved with a `.png` name / `image/png` MIME was checked as PNG
 *    (TRUNCATED_STRUCTURE) → format is now detected from content magic bytes first.
 *  - An old, intact Exif JPEG with an OEM trailer and a large zero tail tripped
 *    TRAILING_ZERO_PADDING → the rule now only applies to recently active files, and never
 *    when the main image's EOI is found before the zero run.
 * Synthetic bytes only.
 */
class SnapshotValidatorFalsePositiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // --- content-based format detection -------------------------------------

    @Test
    fun jpegContentWithPngName_isValid() {
        val bytes = soi + app0Jfif() + tables() + sos() + entropy(300_000) + eoi
        assertValid(validate(bytes, "711084c0.png", "image/png", NOW - 1 * MIN))
        assertValid(validate(bytes, "license.png", "image/png", null))
    }

    @Test
    fun truncatedJpegContentWithPngName_isTruncatedAsJpeg() {
        val bytes = soi + app0Jfif() + tables() + sos() + entropy(300_000)
        val result = validate(bytes, "711084c0.png", "image/png", NOW - 1 * MIN)
        assertReason(SnapshotValidation.Reason.TRUNCATED_STRUCTURE, result)
        assertTrue("detail=${(result as SnapshotValidation.Invalid).detail}", result.detail.contains("format=JPEG"))
    }

    @Test
    fun pngContentWithJpgName_isCheckedAsPng() {
        assertValid(validate(buildPng(), "x.jpg", "image/jpeg", OLD))
        val truncated = buildPng().let { it.copyOf(it.size - 8) }
        val result = validate(truncated, "x.jpg", "image/jpeg", OLD)
        assertReason(SnapshotValidation.Reason.TRUNCATED_STRUCTURE, result)
        assertTrue((result as SnapshotValidation.Invalid).detail.contains("format=PNG"))
    }

    @Test
    fun otherFormatsWithWrongName_areCheckedByContent() {
        assertValid(validate(buildGif(), "x.png", "image/png", OLD))
        assertValid(validate(buildWebp(), "x.jpg", "image/jpeg", OLD))
        assertValid(validate(buildIsoBmff(), "x.jpg", "image/jpeg", OLD))
    }

    @Test
    fun videoWithFtypHeader_keepsUnknownPassThrough() {
        val bytes = be32(16) + ascii("ftyp") + ascii("isom") + be32(0) +
            be32(0x7FFFFFFF) + ascii("mdat") + safeBytes(100)
        assertValid(validate(bytes, "clip.mp4", "video/mp4", OLD))
    }

    // --- trailing-zero rule: JPEG main-image evidence -----------------------

    @Test
    fun smartisanShapedJpeg_withOemTrailerAndZeroTail_isValid() {
        val oemTrailer = ByteArray(8) { 0xD9.toByte() } + ByteArray(4_496) { (0xA1 + it % 11).toByte() }
        val bytes = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(1_100_000) + eoi +
            oemTrailer + ByteArray(184_032)
        val name = "IMG_20170624_113056R.jpg"
        for (ts in listOf(NOW - 7 * 365 * 24 * 60 * MIN, NOW - 1 * MIN, null, NOW + 60 * MIN)) {
            assertValid(validate(bytes, name, "image/jpeg", ts), "lastActivityMs=$ts")
        }
    }

    @Test
    fun preallocatedJpeg_recentOrUnknown_isPadding_oldIsTruncated() {
        val bytes = soi + app0Jfif() + tables() + sos() + entropy(50_000) + ByteArray(200_000)
        val padding = SnapshotValidation.Reason.TRAILING_ZERO_PADDING
        val truncated = SnapshotValidation.Reason.TRUNCATED_STRUCTURE
        assertReason(padding, validate(bytes, "p.jpg", "image/jpeg", NOW - 30_000))
        assertReason(padding, validate(bytes, "p.jpg", "image/jpeg", null))
        assertReason(padding, validate(bytes, "p.jpg", "image/jpeg", NOW + 60 * MIN))
        assertReason(padding, validate(bytes, "p.jpg", "image/jpeg", NOW - 10 * MIN + 1))
        assertReason(truncated, validate(bytes, "p.jpg", "image/jpeg", NOW - 10 * MIN))
        assertReason(truncated, validate(bytes, "p.jpg", "image/jpeg", NOW - 11 * MIN))
    }

    @Test
    fun thumbnailEoiIsNotMainImageEoi() {
        val noMainEoi = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(30_000)
        assertReason(
            SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
            validate(noMainEoi + ByteArray(100_000), "t.jpg", "image/jpeg", NOW - 1 * MIN)
        )
        // < 1MB, so the thumbnail's FF D9 lies inside the old tail window; still truncated.
        assertReason(
            SnapshotValidation.Reason.TRUNCATED_STRUCTURE,
            validate(noMainEoi, "t.jpg", "image/jpeg", OLD)
        )
    }

    @Test
    fun stuffingAndRstMarkers_doNotCountAsEoi() {
        var data = entropy(1_000) + byteArrayOf(0xFF.toByte(), 0x00) + entropy(1_000)
        for (rst in 0xD0..0xD7) data += byteArrayOf(0xFF.toByte(), rst.toByte()) + entropy(500)
        val body = soi + app0Jfif() + tables() + sos() + data
        assertValid(validate(body + eoi + ByteArray(100_000), "s.jpg", "image/jpeg", NOW - 1 * MIN))
        assertReason(
            SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
            validate(body + ByteArray(100_000), "s.jpg", "image/jpeg", NOW - 1 * MIN)
        )
    }

    @Test
    fun unparseableJpeg_keepsPaddingRule() {
        val ffZero = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00) + entropy(1_000) + ByteArray(100_000)
        assertReason(
            SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
            validate(ffZero, "u.jpg", "image/jpeg", NOW - 1 * MIN)
        )
        val overrun = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            entropy(1_000) + ByteArray(100_000)
        assertReason(
            SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
            validate(overrun, "u.jpg", "image/jpeg", NOW - 1 * MIN)
        )
    }

    @Test
    fun postEoiTrailers_stillPass() {
        val main = soi + app1ExifWithThumbnail() + tables() + sos() + entropy(3_000) + eoi
        val second = soi + tables() + sos() + entropy(2_000) + eoi
        assertValid(validate(main + second + entropy(4_096), "m.jpg", "image/jpeg", NOW - 1 * MIN))
        // Main EOI further back than JPEG_EOI_SEARCH_WINDOW.
        assertValid(validate(main + entropy(1_200_000), "m.jpg", "image/jpeg", NOW - 1 * MIN))
    }

    @Test
    fun oldPngWithZeroPadding_isStillCaughtByStructure() {
        assertReason(
            SnapshotValidation.Reason.TRUNCATED_STRUCTURE,
            validate(buildPng() + ByteArray(100_000), "x.png", "image/png", OLD)
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun validate(bytes: ByteArray, name: String, mime: String?, lastActivityMs: Long?): SnapshotValidation {
        val file = File.createTempFile("snapshot", ".bin", tempFolder.root).apply { writeBytes(bytes) }
        return SnapshotValidator.validate(
            snapshot = file,
            snapshotSize = bytes.size.toLong(),
            expectedSize = bytes.size.toLong(),
            fileName = name,
            mimeType = mime,
            lastActivityMs = lastActivityMs,
            nowMs = NOW
        )
    }

    private fun assertValid(result: SnapshotValidation, message: String = "") =
        assertTrue("expected Valid $message but got $result", result is SnapshotValidation.Valid)

    private fun assertReason(expected: SnapshotValidation.Reason, result: SnapshotValidation) {
        assertTrue("expected Invalid($expected) but got $result", result is SnapshotValidation.Invalid)
        assertEquals(expected, (result as SnapshotValidation.Invalid).reason)
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun app0Jfif(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ascii("JFIF\u0000") +
            byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)

    /** APP1 Exif segment that embeds a complete thumbnail JPEG (`FF D8 … FF D9`). */
    private fun app1ExifWithThumbnail(): ByteArray {
        val thumbnail = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 0x01, 0x02,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02
        ) + entropy(200) + eoi
        val content = ascii("Exif\u0000\u0000") + entropy(64) + thumbnail + entropy(32)
        val len = content.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len ushr 8).toByte(), (len and 0xFF).toByte()) + content
    }

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

    /** Payload bytes in 0x01..0x2E: never a format end marker nor zero. */
    private fun safeBytes(n: Int): ByteArray = ByteArray(n) { (it % 46 + 1).toByte() }

    private fun buildPng(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + safeBytes(64) +
            byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

    private fun buildGif(): ByteArray = ascii("GIF89a") + safeBytes(64) + byteArrayOf(0x3B)

    private fun buildWebp(): ByteArray {
        val payload = safeBytes(64)
        return ascii("RIFF") + le32(4 + payload.size) + ascii("WEBP") + payload
    }

    private fun buildIsoBmff(): ByteArray {
        val payload = safeBytes(64)
        return be32(16) + ascii("ftyp") + ascii("mif1") + be32(0) +
            be32(8 + payload.size) + ascii("mdat") + payload
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun le32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun be32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MIN = 60_000L
        const val OLD = NOW - 60 * MIN
    }
}
