package com.huoyi.photovault.service

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Result of validating a staged snapshot before upload (skip-incomplete-media-backup R3).
 *
 * A snapshot is [Valid] when it passes size-consistency, trailing-zero-padding, and
 * (where applicable) structural-integrity checks. Otherwise it is [Invalid] with a
 * machine-readable [Reason] and a human-readable [detail] for diagnostics.
 */
sealed interface SnapshotValidation {
    /** The snapshot passed all applicable checks and may be uploaded. */
    object Valid : SnapshotValidation

    /**
     * The snapshot appears not-fully-written / corrupt and upload must be aborted.
     *
     * @param reason machine-readable failure category
     * @param detail human-readable context for diagnostic logging
     */
    data class Invalid(val reason: Reason, val detail: String) : SnapshotValidation

    /** Categories of snapshot validation failure. */
    enum class Reason {
        /** Snapshot byte size did not strictly equal the trusted MediaStore.SIZE. (R3.1) */
        SIZE_MISMATCH,

        /** Snapshot ends with an abnormally large run of 0x00 (pre-allocation not filled). (R3.2) */
        TRAILING_ZERO_PADDING,

        /** Format-specific structure indicates the file is truncated / incomplete. (R3.3) */
        TRUNCATED_STRUCTURE
    }
}

/**
 * Pure, Android-independent validator that inspects a staged snapshot to detect media that was
 * still being written (pre-allocated but not fully finalized) before it is uploaded.
 *
 * Design goals: strict size equality when a trusted size is available, conservative pass-through
 * when a rule's evidence is unavailable (avoid false-positives on normal files), and bounded,
 * streaming reads (head/tail bytes, or a fixed-buffer JPEG marker walk) so large files are never
 * loaded into memory. (R3, R4.3)
 *
 * The format is detected from content magic bytes first (extension / MIME only as a fallback),
 * and the trailing-zero rule is limited to recently-active files (see [validate]).
 */
object SnapshotValidator {
    /**
     * Validate a staged snapshot.
     *
     * @param snapshot     the staged on-disk snapshot file
     * @param snapshotSize exact byte size of the snapshot (from FileHasher)
     * @param expectedSize MediaStore.SIZE if trustworthy, else null (R4.3 conservative skip)
     * @param fileName     format hint (extension) when the content header is not recognized
     * @param mimeType     secondary format hint when the content header is not recognized
     * @param lastActivityMs newest known activity timestamp of the source (max of MediaStore
     *         DATE_MODIFIED / DATE_ADDED), epoch ms; null or <= 0 = unknown. The trailing-zero
     *         rule only applies when this is within [BackupTuning.TRAILING_ZERO_RECENT_WINDOW_MS]
     *         of [nowMs]; unknown or future timestamps are treated as recent (rule applies).
     * @param nowMs        current time, epoch ms (injectable for tests)
     * @return [SnapshotValidation.Valid] if the snapshot may be uploaded, otherwise
     *         [SnapshotValidation.Invalid] describing the failure.
     */
    fun validate(
        snapshot: File,
        snapshotSize: Long,
        expectedSize: Long?,
        fileName: String,
        mimeType: String?,
        lastActivityMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): SnapshotValidation {
        // 1. Size consistency (R3.1, strict equality / zero tolerance).
        //    Only enforced when a trustworthy expectedSize is available; otherwise skip (R4.3).
        if (expectedSize != null && expectedSize != snapshotSize) {
            return SnapshotValidation.Invalid(
                SnapshotValidation.Reason.SIZE_MISMATCH,
                "expectedSize=$expectedSize snapshotSize=$snapshotSize"
            )
        }

        // Content-based format (magic bytes), null when the header is not recognized.
        val sniffed = sniffFormat(snapshot, fileName, mimeType)

        // 2. Trailing zero padding (R3.2, R4.2).
        //    Flag as corrupt when the run of trailing 0x00 bytes >= max(ABS, size * RATIO),
        //    but only when the file was recently active (a writer in progress keeps bumping the
        //    mtime) and, for JPEG content, only when the main image's EOI is not found before
        //    the zero run (an intact JPEG may carry OEM zero padding after its EOI).
        val trailingZeroThreshold = maxOf(
            BackupTuning.TRAILING_ZERO_MIN_ABS.toLong(),
            (snapshotSize * BackupTuning.TRAILING_ZERO_MIN_RATIO).toLong()
        )
        val trailingZeros = countTrailingZeros(snapshot, snapshotSize, trailingZeroThreshold)
        if (trailingZeros != null && trailingZeros >= trailingZeroThreshold &&
            isRecentlyActive(lastActivityMs, nowMs)
        ) {
            // scanEnd excludes the counted zero tail (the count is capped at the threshold, so
            // scanEnd is still at or after the real start of the run): a Found EOI precedes it.
            val mainEoiBeforeZeros = sniffed == Format.JPEG &&
                JpegMainImageScanner.findMainImageEoi(snapshot, snapshotSize - trailingZeros) is
                JpegEoiScan.Found
            if (!mainEoiBeforeZeros) {
                return SnapshotValidation.Invalid(
                    SnapshotValidation.Reason.TRAILING_ZERO_PADDING,
                    "trailingZeros=$trailingZeros threshold=$trailingZeroThreshold snapshotSize=$snapshotSize " +
                        "lastActivityMs=$lastActivityMs nowMs=$nowMs"
                )
            }
        }

        // 3. Format-specific structural integrity checks (R3.3, R3.7).
        //    Dispatch by content magic bytes first, then file extension, then the MIME hint.
        //    Each checker returns: true = structure intact, false = truncated, null = evidence
        //    insufficient. Unrecognized formats / insufficient evidence conservatively pass
        //    through (R4.3); the size and trailing-zero checks above caught obvious corruption.
        val format = detectFormat(sniffed, fileName, mimeType)
        val structureIntact: Boolean? = when (format) {
            Format.JPEG -> checkJpeg(snapshot, snapshotSize)
            Format.PNG -> checkPng(snapshot)
            Format.GIF -> checkGif(snapshot)
            Format.WEBP -> checkWebp(snapshot, snapshotSize)
            Format.ISO_BMFF -> checkIsoBmff(snapshot)
            Format.UNKNOWN -> null
        }
        if (structureIntact == false) {
            return SnapshotValidation.Invalid(
                SnapshotValidation.Reason.TRUNCATED_STRUCTURE,
                "format=$format snapshotSize=$snapshotSize"
            )
        }

        return SnapshotValidation.Valid
    }

    /** Media container formats this validator knows how to structurally check. */
    private enum class Format { JPEG, PNG, GIF, WEBP, ISO_BMFF, UNKNOWN }

    /**
     * True when the trailing-zero rule should apply: [lastActivityMs] is unknown (null / <= 0),
     * in the future (clock skew), or strictly within
     * [BackupTuning.TRAILING_ZERO_RECENT_WINDOW_MS] of [nowMs].
     */
    private fun isRecentlyActive(lastActivityMs: Long?, nowMs: Long): Boolean =
        lastActivityMs == null || lastActivityMs <= 0L || lastActivityMs > nowMs ||
            nowMs - lastActivityMs < BackupTuning.TRAILING_ZERO_RECENT_WINDOW_MS

    /**
     * Resolve the container [Format]: content magic bytes ([sniffed]) win, so a JPEG saved with
     * a `.png` name / `image/png` MIME is checked as JPEG. Only when the header is not
     * recognized do the file extension and then the MIME hint decide.
     */
    private fun detectFormat(sniffed: Format?, fileName: String, mimeType: String?): Format =
        sniffed ?: formatFromNameOrMime(fileName, mimeType)

    /**
     * Identify the format from the first (up to) 12 header bytes: `FF D8 FF` → JPEG,
     * `89 50 4E 47 0D 0A 1A 0A` → PNG, `GIF87a`/`GIF89a` → GIF, `RIFF....WEBP` → WEBP,
     * `ftyp` at bytes 4..7 → ISO-BMFF. An `ftyp` header is ignored for videos (MP4/MOV share
     * it; videos keep the UNKNOWN pass-through). Returns null when nothing matches or the file
     * cannot be read.
     */
    private fun sniffFormat(snapshot: File, fileName: String, mimeType: String?): Format? {
        val head = try {
            RandomAccessFile(snapshot, "r").use { raf ->
                val n = minOf(SNIFF_LENGTH.toLong(), raf.length()).toInt()
                ByteArray(n).also { raf.readFully(it) }
            }
        } catch (e: IOException) {
            return null
        }
        fun u(i: Int) = head[i].toInt() and 0xFF
        fun ascii(from: Int, text: String) =
            text.indices.all { head[from + it].toInt() == text[it].code }

        if (head.size >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return Format.JPEG
        if (head.size >= 8 && (0 until 8).all { head[it] == PNG_SIGNATURE[it] }) return Format.PNG
        if (head.size >= 6 && (ascii(0, "GIF87a") || ascii(0, "GIF89a"))) return Format.GIF
        if (head.size >= 12 && ascii(0, "RIFF") && ascii(8, "WEBP")) return Format.WEBP
        if (head.size >= 8 && ascii(4, "ftyp") && !isVideo(fileName, mimeType)) return Format.ISO_BMFF
        return null
    }

    private fun isVideo(fileName: String, mimeType: String?): Boolean =
        mimeType?.lowercase()?.startsWith("video/") == true ||
            fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /**
     * Resolve the container [Format] from the file extension (primary) or the MIME hint
     * (secondary). Returns [Format.UNKNOWN] when neither identifies a supported format,
     * which leads to conservative pass-through (R4.3).
     */
    private fun formatFromNameOrMime(fileName: String, mimeType: String?): Format {
        val byExt = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> Format.JPEG
            "png" -> Format.PNG
            "gif" -> Format.GIF
            "webp" -> Format.WEBP
            "heic", "heif", "hif", "avif" -> Format.ISO_BMFF
            else -> Format.UNKNOWN
        }
        if (byExt != Format.UNKNOWN) return byExt

        val mime = mimeType?.lowercase() ?: return Format.UNKNOWN
        return when {
            mime.contains("jpeg") -> Format.JPEG
            mime.contains("png") -> Format.PNG
            mime.contains("gif") -> Format.GIF
            mime.contains("webp") -> Format.WEBP
            mime.contains("heic") || mime.contains("heif") || mime.contains("avif") -> Format.ISO_BMFF
            else -> Format.UNKNOWN
        }
    }

    /**
     * JPEG (jpg/jpeg): a complete image ends with the EOI marker `FF D9`. (R3.3)
     *
     * A well-formed JPEG usually ends *exactly* on `FF D9` (fast path). However, phone cameras
     * frequently append data after the primary image's EOI — Ultra HDR gain maps, MPF
     * multi-picture payloads, or trailing XMP/MakerNote/OEM index (e.g. OnePlus `jxrs`) — so a
     * complete file may not end on `FF D9`. In that case we walk the marker stream with
     * [JpegMainImageScanner] to find the *main* image's EOI (the Exif thumbnail's `FF D9` inside
     * APP1 does not count): found → intact, whatever trailer follows; the stream ends before
     * it → truncated. When the stream cannot be parsed, fall back to scanning backward from EOF
     * within [BackupTuning.JPEG_EOI_SEARCH_WINDOW] for the last `FF D9`: finding it means the
     * embedded image completed and only a small trailer follows (intact); not finding it within
     * the window means the stream was cut before any EOI (truncated).
     */
    private fun checkJpeg(snapshot: File, snapshotSize: Long): Boolean? {
        // Fast path: a normal JPEG ends exactly on the EOI marker.
        val tail = readBytes(snapshot, offsetFromEnd = 2, length = 2) ?: return null
        if ((tail[0].toInt() and 0xFF) == 0xFF && (tail[1].toInt() and 0xFF) == 0xD9) return true
        // Otherwise the file may carry a post-EOI trailer (Ultra HDR / MPF / metadata).
        return when (JpegMainImageScanner.findMainImageEoi(snapshot, snapshotSize)) {
            is JpegEoiScan.Found -> true
            JpegEoiScan.NotFound -> false
            // Unparseable marker stream: look for the last EOI within a bounded tail window.
            JpegEoiScan.Inconclusive -> jpegHasEoiInTailWindow(snapshot)
        }
    }

    /**
     * Scan backward from EOF for the `FF D9` EOI marker, reading at most
     * [BackupTuning.JPEG_EOI_SEARCH_WINDOW] bytes. Returns true if an EOI is found (complete
     * image with trailing data), false if none is found within the window (truncated), or null
     * if the file cannot be read (insufficient evidence → conservative pass-through, R4.3).
     */
    private fun jpegHasEoiInTailWindow(snapshot: File): Boolean? {
        return try {
            RandomAccessFile(snapshot, "r").use { raf ->
                val length = raf.length()
                if (length < 2L) return null
                val window = minOf(length, BackupTuning.JPEG_EOI_SEARCH_WINDOW.toLong())
                val start = length - window
                val buffer = ByteArray(window.toInt())
                raf.seek(start)
                raf.readFully(buffer)
                // Search backward for the FF D9 byte pair.
                var i = buffer.size - 2
                while (i >= 0) {
                    if ((buffer[i].toInt() and 0xFF) == 0xFF &&
                        (buffer[i + 1].toInt() and 0xFF) == 0xD9
                    ) {
                        return true
                    }
                    i--
                }
                // No EOI within the window. If the window covers the whole file, the image never
                // completed → truncated. If the window is only a slice of a larger file, an EOI
                // could exist further back; treat as insufficient evidence rather than reject.
                if (window == length) false else null
            }
        } catch (e: IOException) {
            null
        }
    }

    /** PNG: a complete file ends with the IEND chunk + CRC `49 45 4E 44 AE 42 60 82`. (R3.3) */
    private fun checkPng(snapshot: File): Boolean? {
        val tail = readBytes(snapshot, offsetFromEnd = 8, length = 8) ?: return null
        return tail.contentEquals(PNG_IEND)
    }

    /**
     * GIF: header must be `GIF87a`/`GIF89a` and the file must end with the trailer `0x3B`. (R3.3)
     * A non-GIF header is treated as insufficient evidence (null), not a truncation.
     */
    private fun checkGif(snapshot: File): Boolean? {
        val head = readBytes(snapshot, offsetFromEnd = null, length = 6) ?: return null
        val signature = String(head, Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") return null
        val tail = readBytes(snapshot, offsetFromEnd = 1, length = 1) ?: return null
        return (tail[0].toInt() and 0xFF) == 0x3B
    }

    /**
     * WebP (RIFF): header `RIFF....WEBP`, and the little-endian RIFF chunk length at offset 4
     * must equal `snapshotSize - 8` (the 8-byte `RIFF`+length prefix is excluded). (R3.3)
     * A non-RIFF/WEBP header is treated as insufficient evidence (null).
     */
    private fun checkWebp(snapshot: File, snapshotSize: Long): Boolean? {
        val head = readBytes(snapshot, offsetFromEnd = null, length = 12) ?: return null
        val isRiff = head[0].toInt() == 'R'.code && head[1].toInt() == 'I'.code &&
            head[2].toInt() == 'F'.code && head[3].toInt() == 'F'.code
        val isWebp = head[8].toInt() == 'W'.code && head[9].toInt() == 'E'.code &&
            head[10].toInt() == 'B'.code && head[11].toInt() == 'P'.code
        if (!isRiff || !isWebp) return null
        val declaredLength = (head[4].toLong() and 0xFF) or
            ((head[5].toLong() and 0xFF) shl 8) or
            ((head[6].toLong() and 0xFF) shl 16) or
            ((head[7].toLong() and 0xFF) shl 24)
        return declaredLength == snapshotSize - 8
    }

    /**
     * ISO base media file format (HEIC/HEIF/AVIF): walk the top-level boxes from offset 0,
     * reading only each box header (`size(4) + type(4)`, plus 8 bytes when a 64-bit
     * `largesize` is present). A `size` of 0 means "extends to end of file". The traversal
     * must contain an `ftyp` box, and every recognizable box must fit within the file. (R3.3)
     *
     * Some phone cameras append an opaque OEM trailer after a complete `mdat` (for example,
     * OPPO/Qualcomm `QTI ` debug and watermark metadata). Such bytes are not ISO-BMFF boxes and
     * must not be treated as proof that the image is still being written. Once a complete `mdat`
     * has been seen, an implausible box type or a short opaque tail is therefore insufficient
     * evidence and returns null (conservative pass-through, R4.3). A recognizable box whose
     * declared size exceeds the remaining file is still definite truncation.
     *
     * If no `ftyp` box is present the file is not recognizably ISO-BMFF, so we also return null.
     */
    private fun checkIsoBmff(snapshot: File): Boolean? {
        return try {
            RandomAccessFile(snapshot, "r").use { raf ->
                val length = raf.length()
                if (length <= 0L) return null
                val header = ByteArray(16)
                var position = 0L
                var sawFtyp = false
                var sawCompleteMdat = false
                while (position < length) {
                    val remaining = length - position
                    if (remaining < 8L) {
                        return if (sawCompleteMdat) null
                        else sawFtypOrNull(sawFtyp, truncated = true)
                    }

                    raf.seek(position)
                    raf.readFully(header, 0, 8)

                    // ISO-BMFF box types are printable four-character codes. Binary bytes here,
                    // after a complete media-data box, identify an opaque OEM trailer rather than
                    // a declared box that can be proven truncated.
                    val plausibleType = (4 until 8).all { index ->
                        (header[index].toInt() and 0xFF) in 0x20..0x7E
                    }
                    if (!plausibleType) {
                        return if (sawCompleteMdat) null
                        else sawFtypOrNull(sawFtyp, truncated = true)
                    }

                    var boxSize = readU32BE(header, 0)
                    val type = String(header, 4, 4, Charsets.US_ASCII)
                    var headerSize = 8L
                    when (boxSize) {
                        1L -> {
                            // 64-bit largesize follows the 8-byte header.
                            if (remaining < 16L) {
                                return sawFtypOrNull(sawFtyp, truncated = true)
                            }
                            raf.readFully(header, 8, 8)
                            boxSize = readU64BE(header, 8)
                            headerSize = 16L
                        }
                        0L -> {
                            // Box extends to end of file.
                            boxSize = remaining
                        }
                    }
                    if (type == "ftyp") sawFtyp = true
                    // A box smaller than its header or larger than the remaining bytes is
                    // malformed/truncated. Check before addition to avoid Long overflow.
                    if (boxSize < headerSize || boxSize > remaining) {
                        return sawFtypOrNull(sawFtyp, truncated = true)
                    }
                    position += boxSize
                    if (type == "mdat") sawCompleteMdat = true
                }
                if (!sawFtyp) return null
                true
            }
        } catch (e: IOException) {
            // Evidence unavailable: don't let validator IO jitter block a normal backup (R4.3).
            null
        }
    }

    /**
     * Helper for [checkIsoBmff]: once truncation is detected mid-walk, report it as truncated
     * only if the file was already recognizably ISO-BMFF (an `ftyp` box was seen); otherwise
     * return null so an unrecognized file is not falsely flagged (R4.3).
     */
    private fun sawFtypOrNull(sawFtyp: Boolean, truncated: Boolean): Boolean? =
        if (sawFtyp) !truncated else null

    private fun readU32BE(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)

    private fun readU64BE(b: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (b[offset + i].toLong() and 0xFF)
        }
        return result
    }

    /**
     * Read [length] bytes from [snapshot] without loading the whole file. When [offsetFromEnd]
     * is non-null the bytes are read from `fileLength - offsetFromEnd` (tail read); when null
     * they are read from offset 0 (head read).
     *
     * @return the requested bytes, or `null` if the file is shorter than required or cannot be
     *         read (insufficient evidence → conservative pass-through per R4.3).
     */
    private fun readBytes(snapshot: File, offsetFromEnd: Int?, length: Int): ByteArray? {
        if (length <= 0) return null
        return try {
            RandomAccessFile(snapshot, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength < length) return null
                val start = if (offsetFromEnd != null) fileLength - offsetFromEnd else 0L
                if (start < 0L) return null
                val buffer = ByteArray(length)
                raf.seek(start)
                raf.readFully(buffer)
                buffer
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Count the number of consecutive `0x00` bytes at the end of [snapshot], reading only the
     * necessary tail bytes and never loading the whole file into memory.
     *
     * The scan stops early once the run reaches [limit] (since `run >= threshold` already triggers
     * the trailing-zero rule), so at most `limit` bytes are read from the tail.
     *
     * @return the trailing-zero run length (capped at [limit]), or `null` if the snapshot could
     *         not be read (evidence unavailable → caller treats it conservatively per R4.3).
     */
    private fun countTrailingZeros(snapshot: File, snapshotSize: Long, limit: Long): Long? {
        if (snapshotSize <= 0L || limit <= 0L) return 0L
        return try {
            RandomAccessFile(snapshot, "r").use { raf ->
                val length = raf.length()
                if (length <= 0L) return 0L
                val buffer = ByteArray(READ_CHUNK_SIZE)
                var count = 0L
                var position = length
                while (position > 0L && count < limit) {
                    val chunk = minOf(READ_CHUNK_SIZE.toLong(), position).toInt()
                    position -= chunk
                    raf.seek(position)
                    raf.readFully(buffer, 0, chunk)
                    var i = chunk - 1
                    while (i >= 0) {
                        if (buffer[i].toInt() == 0) {
                            count++
                            if (count >= limit) break
                        } else {
                            return count
                        }
                        i--
                    }
                }
                count
            }
        } catch (e: IOException) {
            // Evidence unavailable: don't let validator IO jitter block a normal backup (R4.3).
            null
        }
    }

    /** Tail-read buffer size for trailing-zero scanning. */
    private const val READ_CHUNK_SIZE = 8 * 1024

    /** Header bytes read for magic-byte format sniffing. */
    private const val SNIFF_LENGTH = 12

    /** Video extensions whose `ftyp` header must not route them to the ISO-BMFF image check. */
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "3g2")

    /** PNG file signature (first 8 bytes). */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** PNG IEND chunk type + CRC — the last 8 bytes of a complete PNG file. */
    private val PNG_IEND = byteArrayOf(
        0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )
}
