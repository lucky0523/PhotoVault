package com.photovault.ui.main.tabs

import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Property-based tests for [deriveLocalCounts].
 *
 * Feature: local-folder-backup-status, Property 1: 本地数量派生正确且非负
 *
 * Input space note: `totalImages` and `backedUpImages` originate from the
 * [com.photovault.data.local.entity.BackupFolder] Room columns, which are
 * image counts and therefore always non-negative. Generators are constrained
 * to `[0, Int.MAX_VALUE]` to stay inside this valid input space (a difference
 * of two non-negative Ints never overflows Int).
 */
class LocalBackupStatusTest {

    /**
     * Feature: local-folder-backup-status, Property 1: 本地数量派生正确且非负
     *
     * 对于任意非负整数 totalImages 与 backedUpImages：
     *  - backedUp == max(0, backedUpImages)
     *  - pending  == max(0, totalImages - backedUpImages)  （含 backedUp > total 边界 → pending == 0）
     *  - 两项均为非负整数
     *
     * Validates: Requirements 1.2, 1.3, 1.5, 1.6
     */
    @Test
    fun `Feature local-folder-backup-status, Property 1 - local counts derived correctly and non-negative`() =
        runTest {
            PropertyTesting.defaultIterationCount = 200
            checkAll(Arb.int(0, Int.MAX_VALUE), Arb.int(0, Int.MAX_VALUE)) { totalImages, backedUpImages ->
                val counts = deriveLocalCounts(totalImages, backedUpImages)

                // backedUp == max(0, backedUpImages)  (Requirement 1.2)
                assertEquals(max(0, backedUpImages), counts.backedUp)

                // pending == max(0, totalImages - backedUpImages)  (Requirement 1.3)
                // both operands are non-negative Ints, so the subtraction cannot overflow.
                assertEquals(max(0, totalImages - backedUpImages), counts.pending)

                // both components non-negative  (Requirement 1.5)
                assertTrue(counts.backedUp >= 0)
                assertTrue(counts.pending >= 0)
            }
        }

    /**
     * Feature: local-folder-backup-status, Property 1 (boundary): totalImages == 0
     *
     * 当 totalImages == 0 时，pending 始终为 0（Requirement 1.6）。在合法不变量
     * total >= backedUp >= 0 下，total == 0 意味着 backedUp == 0，两项均展示为 0。
     *
     * Validates: Requirements 1.6
     */
    @Test
    fun `Feature local-folder-backup-status, Property 1 - total zero yields zero pending`() =
        runTest {
            // Realistic invariant: total == 0 implies backedUp == 0 → both counts are 0.
            val zeroCounts = deriveLocalCounts(totalImages = 0, backedUpImages = 0)
            assertEquals(0, zeroCounts.backedUp)
            assertEquals(0, zeroCounts.pending)

            // For any non-negative backedUp, total == 0 still yields pending == 0.
            checkAll(Arb.int(0, Int.MAX_VALUE)) { backedUpImages ->
                val counts = deriveLocalCounts(totalImages = 0, backedUpImages = backedUpImages)
                assertEquals(0, counts.pending)
                assertTrue(counts.pending >= 0)
                assertTrue(counts.backedUp >= 0)
            }
        }

    /**
     * Feature: local-folder-backup-status, Property 1 (boundary): backedUp > total
     *
     * 当 backedUpImages > totalImages（均非负）时，pending 被限制为 0，backedUp 原样保留。
     *
     * Validates: Requirements 1.3, 1.5
     */
    @Test
    fun `Feature local-folder-backup-status, Property 1 - backedUp greater than total yields zero pending`() =
        runTest {
            checkAll(Arb.int(0, Int.MAX_VALUE), Arb.int(0, Int.MAX_VALUE)) { a, b ->
                val total = minOf(a, b)
                val backedUp = maxOf(a, b)
                val counts = deriveLocalCounts(totalImages = total, backedUpImages = backedUp)
                // total <= backedUp  →  pending coerced to 0
                assertEquals(0, counts.pending)
                assertEquals(backedUp, counts.backedUp)
            }
        }

    /**
     * Feature: local-folder-backup-status, Property 1 (four buckets): 四项云端状态计数正确且非负
     *
     * 对于任意非负整数 totalImages / backedUpImages / trashedImages / purgedImages：
     *  - backedUp == max(0, backedUpImages)
     *  - trashed  == max(0, trashedImages)
     *  - purged   == max(0, purgedImages)
     *  - pending  == max(0, totalImages - backedUpImages - trashedImages - purgedImages)
     *  - 四项均为非负整数
     *
     * 使用有界生成器 [0, 1_000_000] 以避免参考计算中的 Int 溢出。
     *
     * Validates: Requirements 1.1, 1.2, 1.3, 1.5, 1.6
     */
    @Test
    fun `Feature local-folder-backup-status, Property 1 - four cloud status counts derived correctly and non-negative`() =
        runTest {
            val bound = Arb.int(0, 1_000_000)
            checkAll(bound, bound, bound, bound) { total, backedUp, trashed, purged ->
                val counts = deriveLocalCounts(total, backedUp, trashed, purged)

                assertEquals(max(0, backedUp), counts.backedUp)
                assertEquals(max(0, trashed), counts.trashed)
                assertEquals(max(0, purged), counts.purged)
                assertEquals(max(0, total - backedUp - trashed - purged), counts.pending)

                assertTrue(counts.backedUp >= 0)
                assertTrue(counts.pending >= 0)
                assertTrue(counts.trashed >= 0)
                assertTrue(counts.purged >= 0)
            }
        }
}
