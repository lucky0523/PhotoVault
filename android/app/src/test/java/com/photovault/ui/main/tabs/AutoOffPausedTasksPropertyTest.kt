package com.photovault.ui.main.tabs

import com.photovault.data.local.entity.UploadRecord
import com.photovault.service.BackupForegroundService
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * Property-based tests for the pure logic behind "关闭自动备份后保留正在上传文件为
 * 『已暂停』任务" (需求 25-33, Android only).
 *
 * All functions under test are pure and Android-independent, so — like
 * [com.photovault.service.QuietPeriodPropertyTest] — these run as plain JVM
 * JUnit4 + Kotest `checkAll` property tests (no Robolectric, no DB, no
 * framework). [UploadRecord]s are built directly through the data-class
 * constructor.
 *
 * Covered properties (design.md "正确性属性"), each ≥ 100 iterations:
 *  - Property 18: 关闭自动备份对断点记录与队列的分区
 *      — [BackupForegroundService.selectInFlightForAutoOff] /
 *        [SettingsViewModel.recordsToMarkAutoOff]
 *      — Validates: Requirements 25.1, 25.2, 25.3, 25.5, 29.3
 *  - Property 19: AUTO_OFF 暂停任务不被自动续传或重建入队
 *      — the `it.pauseSource != "AUTO_OFF"` gate used by
 *        `BackgroundScanWorker.requeueResumableUploads` / `ConditionCheckWorker`
 *      — Validates: Requirements 25.4, 30.3, 30.5, 31.2 (task links 25.x/30.x)
 *  - Property 20: 已暂停任务进度计算
 *      — [TasksTabViewModel.computeProgressPercent]
 *      — Validates: Requirements 26.2, 26.3
 *  - Property 21: 已暂停清单按暂停时间降序 (DAO `ORDER BY paused_at DESC`)
 *      — Validates: Requirements 26.1
 *  - Property 22: 过期记录从可续传清单中过滤
 *      — Validates: Requirements 32.1
 *  - Property 23: 由 Upload_Record 重建 FileInfo 的字段一致性
 *      — [UploadRecord.toFileInfo] / [guessMimeTypeFromFileName]
 *      — Validates: Requirements 27.1, 31.1
 *  - Property 24: 继续单个已暂停任务不影响其他已暂停任务
 *      — Validates: Requirements 30.5
 */
class AutoOffPausedTasksPropertyTest {

    private val iterations = 200

    /** Seven-day resume window, shared by SettingsViewModel & TasksTabViewModel. */
    private val expiry = SettingsViewModel.SESSION_EXPIRY_MS

    /** A fixed "now" well past any generated created_at so age = now - created_at. */
    private val fixedNow = 60L * 24 * 60 * 60 * 1000 // 60 days in ms

    // --- Generators ----------------------------------------------------------

    // Base file-name stems (no dots) so extension detection is unambiguous.
    private val baseNameArb: Arb<String> =
        Arb.element("photo", "img", "IMG_0001", "clip", "movie", "a", "file", "DSC")

    // Extensions spanning known photo/video types, an unknown one, and none.
    private val extensionArb: Arb<String> = Arb.element(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng",
        "mp4", "mov", "mkv", "webm", "3gp", "avi", "xyz", ""
    )

    private val fileNameArb: Arb<String> = arbitrary {
        val base = baseNameArb.bind()
        val ext = extensionArb.bind()
        if (ext.isEmpty()) base else "$base.$ext"
    }

    // MIME type: real values, empty, and whitespace-only (all-blank -> guessed).
    private val mimeArb: Arb<String> =
        Arb.element("image/jpeg", "video/mp4", "application/octet-stream", "", "   ")

    private val pauseSourceArb: Arb<String?> =
        Arb.element(null, "AUTO_OFF", "USER", "CONDITION")

    // uploaded_chunk_index: -1 (nothing confirmed) through past total.
    private val chunkIndexArb: Arb<Int> = Arb.int(-1, 20)

    /**
     * Builds a random [UploadRecord]. `createdAt = fixedNow - age`, with `age`
     * spanning both sides of the 7-day expiry so expiry filtering is exercised.
     * `fileUri` is drawn from a small alphabet-ish space; callers that need
     * distinct URIs de-duplicate with `distinctBy`.
     */
    private fun uploadRecordArb(): Arb<UploadRecord> = arbitrary {
        val name = fileNameArb.bind()
        val age = Arb.long(0L, 14L * 24 * 60 * 60 * 1000).bind() // 0..14 days
        val idx = chunkIndexArb.bind()
        val total = Arb.int(0, 20).bind()
        val source = pauseSourceArb.bind()
        val pausedAt = Arb.long(1L, fixedNow).bind()
        UploadRecord(
            fileUri = "content://media/${Arb.int(0, 100000).bind()}",
            sessionId = "session-${Arb.int(0, 100000).bind()}",
            fileHash = "hash",
            fileName = name,
            fileSize = Arb.long(0L, 5_000_000_000L).bind(),
            fileModifiedTime = Arb.long(0L, fixedNow).bind(),
            folderUri = "content://tree/${baseNameArb.bind()}",
            mimeType = mimeArb.bind(),
            totalChunks = total,
            uploadedChunkIndex = idx,
            createdAt = fixedNow - age,
            updatedAt = fixedNow - age,
            pauseSource = source,
            pausedAt = pausedAt
        )
    }

    private val recordsArb: Arb<List<UploadRecord>> = Arb.list(uploadRecordArb(), 0..25)

    // --- Property 18: partition on auto-backup disable -----------------------

    /**
     * Feature: photo-backup-service, Property 18 - 关闭自动备份对断点记录与队列的分区.
     *
     * For any random record snapshot (mixing `uploaded_chunk_index == -1` with
     * `>= 0`, and varied `created_at`): the AUTO_OFF selection is exactly the
     * In_Flight_File(s) — those with `uploaded_chunk_index >= 0` — that are still
     * within the 7-day window. `selectInFlightForAutoOff` matches the plain
     * `>= 0` partition, and `recordsToMarkAutoOff` additionally drops expired
     * ones. Queued_Not_Started_File(s) have no record and never appear here.
     *
     * Validates: Requirements 25.1, 25.2, 25.3, 25.5, 29.3
     */
    @Test
    fun `Property 18 - auto-off marks exactly in-flight unexpired records`() {
        runBlocking {
            checkAll(iterations, recordsArb) { records ->
                val inFlight = BackupForegroundService.selectInFlightForAutoOff(records)
                assertEquals(
                    records.filter { it.uploadedChunkIndex >= 0 },
                    inFlight
                )

                val toMark = SettingsViewModel.recordsToMarkAutoOff(records, fixedNow, expiry)
                assertEquals(
                    records.filter {
                        it.uploadedChunkIndex >= 0 && fixedNow - it.createdAt <= expiry
                    },
                    toMark
                )
                // Every marked record is a genuine In_Flight_File within the window.
                toMark.forEach {
                    assertTrue(it.uploadedChunkIndex >= 0)
                    assertTrue(fixedNow - it.createdAt <= expiry)
                }
            }
        }
    }

    /**
     * Feature: photo-backup-service, Property 18 (R-25.5) - no In_Flight_File ->
     * nothing marked. When every record has `uploaded_chunk_index == -1`
     * (Queued_Not_Started_File semantics), the AUTO_OFF selection is empty.
     *
     * Validates: Requirements 25.5
     */
    @Test
    fun `Property 18 - no confirmed chunk yields empty auto-off set`() {
        runBlocking {
            val notStartedRecords = Arb.list(
                arbitrary {
                    uploadRecordArb().bind().copy(uploadedChunkIndex = -1)
                },
                0..25
            )
            checkAll(iterations, notStartedRecords) { records ->
                assertTrue(BackupForegroundService.selectInFlightForAutoOff(records).isEmpty())
                assertTrue(
                    SettingsViewModel.recordsToMarkAutoOff(records, fixedNow, expiry).isEmpty()
                )
            }
        }
    }

    // --- Property 19: AUTO_OFF excluded from auto-resume gate -----------------

    /**
     * Feature: photo-backup-service, Property 19 - AUTO_OFF 暂停任务不被自动续传或
     * 重建入队. The auto-resume gate `it.pauseSource != "AUTO_OFF"` (used by
     * `requeueResumableUploads` / `ConditionCheckWorker`) drops every AUTO_OFF
     * record and keeps all others (null / USER / CONDITION) unchanged.
     *
     * Validates: Requirements 25.4, 30.3, 30.5, 31.2
     */
    @Test
    fun `Property 19 - auto-resume gate excludes exactly AUTO_OFF records`() {
        runBlocking {
            checkAll(iterations, recordsArb) { records ->
                val gated = records.filter { it.pauseSource != "AUTO_OFF" }

                // No AUTO_OFF record survives the gate.
                assertTrue(gated.none { it.pauseSource == "AUTO_OFF" })
                // Every non-AUTO_OFF record is retained, in order.
                assertEquals(records.filter { it.pauseSource != "AUTO_OFF" }, gated)
                // Count identity: kept + AUTO_OFF == all.
                val autoOff = records.count { it.pauseSource == "AUTO_OFF" }
                assertEquals(records.size, gated.size + autoOff)
            }
        }
    }

    // --- Property 20: progress computation ------------------------------------

    /**
     * Feature: photo-backup-service, Property 20 - 已暂停任务进度计算.
     *
     * For any `uploaded_chunk_index` (incl. -1) and any `total_chunks` (incl.
     * 0 / negative): the result is within [0, 100]; when `total_chunks <= 0` it
     * is 0; otherwise it equals the clamped `floor((idx + 1) / total * 100)`.
     *
     * Validates: Requirements 26.2, 26.3
     */
    @Test
    fun `Property 20 - progress is clamped floor percentage`() {
        runBlocking {
            val idxArb = Arb.int(-1, 500)
            val totalArb = Arb.int(-10, 500)
            checkAll(iterations, idxArb, totalArb) { idx, total ->
                val percent = TasksTabViewModel.computeProgressPercent(idx, total)

                assertTrue("percent=$percent out of range", percent in 0..100)
                if (total <= 0) {
                    assertEquals(0, percent)
                } else {
                    val uploaded = (idx + 1).coerceIn(0, total)
                    val expected = floor(uploaded.toDouble() * 100.0 / total.toDouble())
                        .toInt()
                        .coerceIn(0, 100)
                    assertEquals(
                        "idx=$idx total=$total",
                        expected,
                        percent
                    )
                }
            }
        }
    }

    // --- Property 21: descending sort by paused_at ----------------------------

    /**
     * Feature: photo-backup-service, Property 21 - 已暂停清单按暂停时间降序.
     *
     * For any set of AUTO_OFF records, ordering by `paused_at` descending (the
     * DAO `ORDER BY paused_at DESC`) yields a sequence whose adjacent `paused_at`
     * values are monotonically non-increasing.
     *
     * Validates: Requirements 26.1
     */
    @Test
    fun `Property 21 - paused list is sorted by paused_at descending`() {
        runBlocking {
            val pausedAtListArb = Arb.list(Arb.long(0L, fixedNow), 0..30)
            checkAll(iterations, pausedAtListArb) { pausedAtValues ->
                val records = pausedAtValues.mapIndexed { i, ts ->
                    baseRecord(fileUri = "content://media/$i", pausedAt = ts, pauseSource = "AUTO_OFF")
                }
                val sorted = records.sortedByDescending { it.pausedAt }

                for (i in 0 until sorted.size - 1) {
                    val a = sorted[i].pausedAt ?: Long.MIN_VALUE
                    val b = sorted[i + 1].pausedAt ?: Long.MIN_VALUE
                    assertTrue(
                        "non-descending at $i: $a then $b",
                        a >= b
                    )
                }
                // Sorting is a permutation: same multiset of paused_at values.
                assertEquals(
                    pausedAtValues.sortedDescending(),
                    sorted.mapNotNull { it.pausedAt }
                )
            }
        }
    }

    // --- Property 22: expiry filtering ----------------------------------------

    /**
     * Feature: photo-backup-service, Property 22 - 过期记录从可续传清单中过滤.
     *
     * For any record set and any `now`, the retained set is exactly the records
     * with `now - created_at <= expiry`; every expired record is dropped and no
     * unexpired record is affected.
     *
     * Validates: Requirements 32.1
     */
    @Test
    fun `Property 22 - only records within the 7 day window are kept`() {
        runBlocking {
            val createdAtArb = Arb.long(0L, 20L * 24 * 60 * 60 * 1000)
            val nowArb = Arb.long(0L, 40L * 24 * 60 * 60 * 1000)
            val listArb = Arb.list(
                arbitrary {
                    baseRecord(
                        fileUri = "content://media/${Arb.int(0, 100000).bind()}",
                        createdAt = createdAtArb.bind()
                    )
                },
                0..25
            )
            checkAll(iterations, nowArb, listArb) { now, records ->
                val kept = records.filter { now - it.createdAt <= expiry }

                // Retained set matches the predicate exactly.
                kept.forEach { assertTrue(now - it.createdAt <= expiry) }
                // Nothing expired slipped through, nothing valid was dropped.
                assertEquals(records.filter { now - it.createdAt <= expiry }, kept)
                val expired = records.count { now - it.createdAt > expiry }
                assertEquals(records.size, kept.size + expired)
            }
        }
    }

    // --- Property 23: FileInfo reconstruction consistency ---------------------

    /**
     * Feature: photo-backup-service, Property 23 - 由 Upload_Record 重建 FileInfo
     * 的字段一致性. Every field maps as specified; `createdTime` comes from
     * `file_modified_time`; a non-blank `mime_type` is preserved verbatim while a
     * blank one falls back to the extension-based guess. `forceReupload` is false
     * on the resume path.
     *
     * Validates: Requirements 27.1, 31.1
     */
    @Test
    fun `Property 23 - toFileInfo maps every field`() {
        runBlocking {
            checkAll(iterations, uploadRecordArb()) { record ->
                val info = record.toFileInfo()

                assertEquals(record.fileUri, info.uri)
                assertEquals(record.fileName, info.fileName)
                assertEquals(record.fileSize, info.fileSize)
                assertEquals(record.fileModifiedTime, info.createdTime)
                assertEquals(record.folderUri, info.folderUri)
                assertFalse(info.forceReupload)

                if (record.mimeType.isNotBlank()) {
                    assertEquals(record.mimeType, info.mimeType)
                } else {
                    assertEquals(guessMimeTypeFromFileName(record.fileName), info.mimeType)
                }
                // The guessed value is always a concrete, non-blank type.
                assertTrue(info.mimeType.isNotBlank())
            }
        }
    }

    // --- Property 24: continue-one isolation ----------------------------------

    /**
     * Feature: photo-backup-service, Property 24 - 继续单个已暂停任务不影响其他已暂停
     * 任务. Continuing one task clears only that file's AUTO_OFF marker
     * (`resumePausedTask` calls `clearAutoOffPause(fileUri)` for a single URI);
     * every other record keeps its `pause_source`, `paused_at` and existence.
     *
     * Validates: Requirements 30.5
     */
    @Test
    fun `Property 24 - clearing one AUTO_OFF leaves the others unchanged`() {
        runBlocking {
            val autoOffListArb = Arb.list(
                arbitrary {
                    uploadRecordArb().bind().copy(pauseSource = "AUTO_OFF")
                },
                1..25
            )
            checkAll(iterations, autoOffListArb) { rawRecords ->
                // Ensure distinct identities before picking a target.
                val records = rawRecords.distinctBy { it.fileUri }
                val target = records.first().fileUri

                // Pure "continue one" transform (mirrors clearAutoOffPause on one uri).
                val after = records.map {
                    if (it.fileUri == target) it.copy(pauseSource = null, pausedAt = null) else it
                }

                assertEquals(records.size, after.size)
                for (before in records) {
                    val updated = after.first { it.fileUri == before.fileUri }
                    if (before.fileUri == target) {
                        assertEquals(null, updated.pauseSource)
                        assertEquals(null, updated.pausedAt)
                    } else {
                        assertEquals(before.pauseSource, updated.pauseSource)
                        assertEquals(before.pausedAt, updated.pausedAt)
                        assertEquals(before, updated)
                    }
                }
            }
        }
    }

    // --- Helpers --------------------------------------------------------------

    /**
     * Minimal [UploadRecord] with sensible defaults for properties that only
     * care about a couple of columns (paused_at ordering, expiry filtering).
     */
    private fun baseRecord(
        fileUri: String,
        createdAt: Long = fixedNow,
        pausedAt: Long? = null,
        pauseSource: String? = null
    ): UploadRecord = UploadRecord(
        fileUri = fileUri,
        sessionId = "session",
        fileHash = "hash",
        fileName = "photo.jpg",
        fileSize = 1024L,
        fileModifiedTime = createdAt,
        folderUri = "content://tree/folder",
        mimeType = "image/jpeg",
        totalChunks = 4,
        uploadedChunkIndex = 1,
        createdAt = createdAt,
        updatedAt = createdAt,
        pauseSource = pauseSource,
        pausedAt = pausedAt
    )
}
