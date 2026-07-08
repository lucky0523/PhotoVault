package com.photovault.service

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Property-based tests for [SnapshotValidator].
 *
 * Feature: skip-incomplete-media-backup — R3 上传前快照合法性校验。
 *
 * [SnapshotValidator] is a pure, Android-independent object, so these tests run as plain
 * JVM JUnit4 + Kotest property tests (no Robolectric). Each generated sample is written to a
 * temp file and its length is passed as `snapshotSize`, exactly mirroring how `FileHasher`
 * feeds the validator at runtime.
 *
 * Generators build a minimal *valid* sample per supported container format (JPEG / PNG / GIF /
 * WebP / ISO-BMFF) and then mutate it (append trailing zeros, truncate from the end) to drive
 * the individual properties. Sample payload bytes are constrained to `0x01..0x2E` so they can
 * never collide with a format's end marker (`FF D9`, IEND CRC, `0x3B`, ...) nor introduce a
 * trailing-zero run — this keeps the *base* samples unambiguously valid so Property 1 holds and
 * makes the mutation-driven failures (Property 2 / 4) deterministic.
 *
 * Covered properties (design.md "Correctness Properties"):
 *  - Property 1: 正常文件放行           — Validates: Requirements 3.6
 *  - Property 2: 零填充必被拦截         — Validates: Requirements 3.2
 *  - Property 3: 大小严格相等           — Validates: Requirements 3.1, 4.5
 *  - Property 4: 截断结构被拦截         — Validates: Requirements 3.3, 3.7
 *  - Property 5: 保守放行不误杀         — Validates: Requirements 4.3
 */
class SnapshotValidatorPropertyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val iterations = 100

    // Payload bytes constrained to 0x01..0x2E: non-zero (no accidental trailing-zero run) and
    // strictly below/aside every format end marker (0x3B GIF trailer, 0xFF/0xD9 JPEG EOI, the
    // PNG IEND CRC bytes 0xAE/0x82, ...), so a valid sample is never ambiguously "truncated".
    private val safeByte: Arb<Byte> = Arb.int(1, 46).map { it.toByte() }

    // Non-empty payloads keep every base sample larger than the smallest structural window.
    private val payloadArb: Arb<ByteArray> = Arb.byteArray(Arb.int(1, 64), safeByte)

    /** A supported container format together with how to build a minimal valid sample. */
    private data class Fmt(
        val label: String,
        val fileName: String,
        val mimeType: String?,
        /** Minimum bytes to keep so the format's header stays readable when truncating. */
        val minKeep: Int,
        val build: (ByteArray) -> ByteArray
    )

    private val formats: List<Fmt> = listOf(
        Fmt("JPEG", "photo.jpg", "image/jpeg", minKeep = 2, build = ::buildJpeg),
        Fmt("PNG", "photo.png", "image/png", minKeep = 8, build = ::buildPng),
        Fmt("GIF", "photo.gif", "image/gif", minKeep = 6, build = ::buildGif),
        Fmt("WEBP", "photo.webp", "image/webp", minKeep = 12, build = ::buildWebp),
        Fmt("ISO_BMFF", "photo.heic", "image/heic", minKeep = 24, build = ::buildIsoBmff)
    )

    private val formatArb: Arb<Fmt> = Arb.of(formats)

    // --- Property 1: normal files pass ---------------------------------------

    /**
     * Feature: skip-incomplete-media-backup, Property 1 - valid media snapshots are passed through.
     *
     * For any structurally-complete sample whose size equals the trusted expectedSize and which
     * has no large trailing-zero run, [SnapshotValidator.validate] returns [SnapshotValidation.Valid].
     *
     * Validates: Requirements 3.6
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 1 - valid snapshot is passed through`() {
        runBlocking {
            checkAll(iterations, formatArb, payloadArb) { fmt, payload ->
                val bytes = fmt.build(payload)
                val file = writeTemp(bytes)
                try {
                    val result = SnapshotValidator.validate(
                        snapshot = file,
                        snapshotSize = bytes.size.toLong(),
                        expectedSize = bytes.size.toLong(),
                        fileName = fmt.fileName,
                        mimeType = fmt.mimeType
                    )
                    assertTrue(
                        "expected Valid for ${fmt.label} (size=${bytes.size}) but got $result",
                        result is SnapshotValidation.Valid
                    )
                } finally {
                    file.delete()
                }
            }
        }
    }

    // --- Property 2: trailing zero padding is rejected -----------------------

    /**
     * Feature: skip-incomplete-media-backup, Property 2 - trailing zero padding is rejected.
     *
     * Appending a run of `0x00` bytes at/above the padding threshold to any valid base sample
     * makes [SnapshotValidator.validate] return [SnapshotValidation.Invalid]. With expectedSize
     * set to the padded size (so the size check passes) the reason is TRAILING_ZERO_PADDING.
     * This reproduces the MVIMG pre-allocation corruption case.
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 2 - trailing zero padding is rejected`() {
        runBlocking {
            // >= TRAILING_ZERO_MIN_ABS (64KB); base samples are tiny so this dominates the ratio.
            val zeroRun = Arb.int(BackupTuning.TRAILING_ZERO_MIN_ABS, BackupTuning.TRAILING_ZERO_MIN_ABS + 4096)
            checkAll(iterations, formatArb, payloadArb, zeroRun) { fmt, payload, zeros ->
                val bytes = fmt.build(payload) + ByteArray(zeros) // ByteArray(zeros) is all 0x00
                val file = writeTemp(bytes)
                try {
                    val result = SnapshotValidator.validate(
                        snapshot = file,
                        snapshotSize = bytes.size.toLong(),
                        expectedSize = bytes.size.toLong(), // pass size check → padding must trip
                        fileName = fmt.fileName,
                        mimeType = fmt.mimeType
                    )
                    assertTrue(
                        "expected Invalid for padded ${fmt.label} but got $result",
                        result is SnapshotValidation.Invalid
                    )
                    assertEquals(
                        SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
                        (result as SnapshotValidation.Invalid).reason
                    )
                } finally {
                    file.delete()
                }
            }
        }
    }

    // --- Property 3: strict size equality ------------------------------------

    /**
     * Feature: skip-incomplete-media-backup, Property 3 - size mismatch is rejected (strict equality).
     *
     * When a trusted expectedSize differs from snapshotSize by >= 1 byte (in either direction),
     * [SnapshotValidator.validate] returns Invalid(SIZE_MISMATCH). Zero tolerance.
     *
     * Validates: Requirements 3.1, 4.5
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 3 - size mismatch is rejected`() {
        runBlocking {
            checkAll(iterations, formatArb, payloadArb, Arb.long(1L, 5_000_000L), Arb.boolean()) { fmt, payload, delta, larger ->
                val bytes = fmt.build(payload)
                val size = bytes.size.toLong()
                // delta >= 1 in either direction always yields expectedSize != size.
                val expected = if (larger) size + delta else (size - delta).coerceAtLeast(0L)
                val file = writeTemp(bytes)
                try {
                    val result = SnapshotValidator.validate(
                        snapshot = file,
                        snapshotSize = size,
                        expectedSize = expected,
                        fileName = fmt.fileName,
                        mimeType = fmt.mimeType
                    )
                    assertTrue(
                        "expected Invalid for size mismatch (${fmt.label}: expected=$expected size=$size) but got $result",
                        result is SnapshotValidation.Invalid
                    )
                    assertEquals(
                        SnapshotValidation.Reason.SIZE_MISMATCH,
                        (result as SnapshotValidation.Invalid).reason
                    )
                } finally {
                    file.delete()
                }
            }
        }
    }

    // --- Property 4: truncated structure is rejected -------------------------

    /**
     * Feature: skip-incomplete-media-backup, Property 4 - truncated structure is rejected.
     *
     * Truncating a valid sample from the end (dropping its end marker / shrinking it below the
     * declared container length) while keeping the header readable yields Invalid(TRUNCATED_STRUCTURE).
     * expectedSize is set to the truncated size to bypass the size check (Property 3), so the
     * failure isolates the structural check. Payload bytes never form an end marker, so the
     * truncated tail is always non-zero / non-marker.
     *
     * Validates: Requirements 3.3, 3.7
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 4 - truncated structure is rejected`() {
        runBlocking {
            checkAll(iterations, formatArb, payloadArb, Arb.int(0, Int.MAX_VALUE)) { fmt, payload, raw ->
                val bytes = fmt.build(payload)
                val maxKeep = bytes.size - 1 // drop at least the final byte
                val range = maxKeep - fmt.minKeep
                val newSize = fmt.minKeep + (raw % (range + 1))
                val truncated = bytes.copyOf(newSize)
                val file = writeTemp(truncated)
                try {
                    val result = SnapshotValidator.validate(
                        snapshot = file,
                        snapshotSize = newSize.toLong(),
                        expectedSize = newSize.toLong(), // bypass size check
                        fileName = fmt.fileName,
                        mimeType = fmt.mimeType
                    )
                    assertTrue(
                        "expected Invalid(TRUNCATED_STRUCTURE) for truncated ${fmt.label} (newSize=$newSize of ${bytes.size}) but got $result",
                        result is SnapshotValidation.Invalid &&
                            result.reason == SnapshotValidation.Reason.TRUNCATED_STRUCTURE
                    )
                } finally {
                    file.delete()
                }
            }
        }
    }

    // --- Property 5: conservative pass-through -------------------------------

    /**
     * Feature: skip-incomplete-media-backup, Property 5 - conservative pass-through of unknown formats.
     *
     * With expectedSize == null (untrusted size), an unrecognized format (fileName "file.bin",
     * null MIME), and no large trailing-zero run, [SnapshotValidator.validate] returns Valid —
     * a rule whose evidence is unavailable must not falsely reject a file (R4.3).
     *
     * Validates: Requirements 4.3
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 5 - unknown format with no evidence is passed through`() {
        runBlocking {
            // Non-zero bytes only: guarantees no trailing-zero run to trip the padding rule.
            val bodyArb = Arb.byteArray(Arb.int(0, 256), Arb.int(1, 255).map { it.toByte() })
            checkAll(iterations, bodyArb) { bytes ->
                val file = writeTemp(bytes)
                try {
                    val result = SnapshotValidator.validate(
                        snapshot = file,
                        snapshotSize = bytes.size.toLong(),
                        expectedSize = null,
                        fileName = "file.bin",
                        mimeType = null
                    )
                    assertTrue(
                        "expected Valid for unknown format (size=${bytes.size}) but got $result",
                        result is SnapshotValidation.Valid
                    )
                } finally {
                    file.delete()
                }
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun writeTemp(bytes: ByteArray): File {
        val file = File.createTempFile("snapshot", ".bin", tempFolder.root)
        file.writeBytes(bytes)
        return file
    }

    // --- format sample builders ---------------------------------------------

    /** JPEG: SOI `FF D8` + payload + EOI `FF D9`. */
    private fun buildJpeg(payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + payload + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** PNG: 8-byte signature + payload + IEND chunk type & CRC (last 8 bytes). */
    private fun buildPng(payload: ByteArray): ByteArray =
        PNG_SIGNATURE + payload + PNG_IEND

    /** GIF: `GIF89a` header + payload + trailer `0x3B`. */
    private fun buildGif(payload: ByteArray): ByteArray =
        "GIF89a".toByteArray(Charsets.US_ASCII) + payload + byteArrayOf(0x3B)

    /** WebP (RIFF): `RIFF` + LE32(size-8) + `WEBP` + payload. */
    private fun buildWebp(payload: ByteArray): ByteArray {
        val total = 12 + payload.size
        val declared = total - 8
        return "RIFF".toByteArray(Charsets.US_ASCII) +
            le32(declared) +
            "WEBP".toByteArray(Charsets.US_ASCII) +
            payload
    }

    /**
     * ISO-BMFF (HEIC/HEIF/AVIF): a 16-byte `ftyp` box followed by an `mdat` box holding the
     * payload. Top-level box sizes sum exactly to the file length and an `ftyp` box is present.
     */
    private fun buildIsoBmff(payload: ByteArray): ByteArray {
        val ftyp = be32(16) +
            "ftyp".toByteArray(Charsets.US_ASCII) +
            "mif1".toByteArray(Charsets.US_ASCII) + // major brand
            be32(0)                                  // minor version
        val mdatSize = 8 + payload.size
        val mdat = be32(mdatSize) + "mdat".toByteArray(Charsets.US_ASCII) + payload
        return ftyp + mdat
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val PNG_IEND = byteArrayOf(
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
    }
}
