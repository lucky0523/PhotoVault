package com.photovault.service

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based tests for [QuietPeriodLogic].
 *
 * Feature: skip-incomplete-media-backup — R1 静默期跳过 + 不漏扫.
 *
 * [QuietPeriodLogic] is a pure, Android-independent object, so these tests run as plain JVM
 * JUnit4 + Kotest property tests (no Robolectric).
 *
 * Covered properties (design.md "Correctness Properties"):
 *  - Property 6: 静默期单调性 + dateModified==0   — Validates: Requirements 1.1, 1.5
 *  - Property 7: lastScanTime 不漏扫              — Validates: Requirements 1.2
 *
 * Generator bounds note: several properties compute `now = dateModified + offset`. To keep that
 * sum safely inside the Long range (and mirror realistic epoch-millisecond magnitudes),
 * `dateModified` is bounded to `1..10_000_000_000` (well past year 2286 in ms) and `offset` to
 * `0..10_000_000` (~2.7h), so `dateModified + offset` can never overflow.
 */
class QuietPeriodPropertyTest {

    private val iterations = 200

    private val quietPeriod = BackupTuning.QUIET_PERIOD_MS

    // dateModified constrained to positive, realistic epoch-ms magnitudes.
    private val dateModifiedArb: Arb<Long> = Arb.long(1L, 10_000_000_000L)

    // offset kept small enough that dateModified + offset never overflows Long.
    private val offsetArb: Arb<Long> = Arb.long(0L, 10_000_000L)

    // --- Property 6: quiet-period monotonicity + dateModified == 0 -----------

    /**
     * Feature: skip-incomplete-media-backup, Property 6 - quiet period skip is monotonic in the offset.
     *
     * For any file with `dateModified > 0` and any offset `>= 0`, with `now = dateModified + offset`,
     * `shouldSkipForQuietPeriod(now, dateModified)` is true iff `offset < QUIET_PERIOD_MS`.
     * The generator spans offsets both below and above the threshold.
     *
     * Validates: Requirements 1.1, 1.5
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 6 - skip iff offset below quiet period`() {
        runBlocking {
            checkAll(iterations, dateModifiedArb, offsetArb) { dateModified, offset ->
                val now = dateModified + offset
                val skipped = QuietPeriodLogic.shouldSkipForQuietPeriod(now, dateModified)
                assertEquals(
                    "dateModified=$dateModified offset=$offset now=$now quietPeriod=$quietPeriod",
                    offset < quietPeriod,
                    skipped
                )
            }
        }
    }

    /**
     * Feature: skip-incomplete-media-backup, Property 6 (boundary) - exactly at the threshold is not skipped.
     *
     * At `offset == QUIET_PERIOD_MS` the file is considered stable and is NOT skipped, and one
     * millisecond below the threshold it IS skipped.
     *
     * Validates: Requirements 1.1
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 6 - quiet period boundary is exclusive`() {
        runBlocking {
            checkAll(iterations, dateModifiedArb) { dateModified ->
                // exactly at the boundary -> not skipped
                assertFalse(
                    "offset == QUIET_PERIOD_MS must not skip (dateModified=$dateModified)",
                    QuietPeriodLogic.shouldSkipForQuietPeriod(dateModified + quietPeriod, dateModified)
                )
                // one ms below the boundary -> skipped
                assertTrue(
                    "offset == QUIET_PERIOD_MS - 1 must skip (dateModified=$dateModified)",
                    QuietPeriodLogic.shouldSkipForQuietPeriod(dateModified + quietPeriod - 1, dateModified)
                )
            }
        }
    }

    /**
     * Feature: skip-incomplete-media-backup, Property 6 (dateModified == 0) - never skipped.
     *
     * When `dateModified == 0` (missing / unavailable timestamp) the file is never skipped,
     * regardless of `now`, so a missing timestamp can never permanently exclude a file.
     *
     * Validates: Requirements 1.5
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 6 - dateModified zero is never skipped`() {
        runBlocking {
            checkAll(iterations, Arb.long(Long.MIN_VALUE, Long.MAX_VALUE)) { now ->
                assertFalse(
                    "dateModified == 0 must never skip (now=$now)",
                    QuietPeriodLogic.shouldSkipForQuietPeriod(now, 0L)
                )
            }
        }
    }

    // --- Property 7: lastScanTime never misses skipped files -----------------

    /**
     * Feature: skip-incomplete-media-backup, Property 7 - next scan time never misses skipped files.
     *
     * For any non-empty list of skipped `dateModified` values (positive Longs) and any
     * `currentTime`, `computeNextScanTime` returns a value `<=` every skipped element and
     * `<= currentTime`. The next scan enqueues items with `DATE_MODIFIED > lastScanTime`, so a
     * `lastScanTime` at/below the earliest skipped modification guarantees those files are seen again.
     *
     * Validates: Requirements 1.2
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 7 - next scan time does not skip past skipped files`() {
        runBlocking {
            val skippedArb = Arb.list(dateModifiedArb, 1..20)
            checkAll(iterations, dateModifiedArb, skippedArb) { currentTime, skipped ->
                val next = QuietPeriodLogic.computeNextScanTime(currentTime, skipped)

                assertTrue(
                    "next=$next must be <= currentTime=$currentTime",
                    next <= currentTime
                )
                skipped.forEach { modified ->
                    assertTrue(
                        "next=$next must be <= skipped modified=$modified (skipped=$skipped)",
                        next <= modified
                    )
                }
            }
        }
    }

    /**
     * Feature: skip-incomplete-media-backup, Property 7 (empty) - no skipped files preserves currentTime.
     *
     * When nothing was skipped this scan, `computeNextScanTime` returns `currentTime` unchanged,
     * preserving normal incremental-scan behavior.
     *
     * Validates: Requirements 1.2
     */
    @Test
    fun `Feature skip-incomplete-media-backup, Property 7 - empty skipped list preserves current time`() {
        runBlocking {
            checkAll(iterations, Arb.long(Long.MIN_VALUE, Long.MAX_VALUE)) { currentTime ->
                assertEquals(
                    currentTime,
                    QuietPeriodLogic.computeNextScanTime(currentTime, emptyList())
                )
            }
        }
    }
}
