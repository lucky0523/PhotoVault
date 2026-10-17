package com.huoyi.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [checkActiveFileSize]. */
class ModifiedFileDetectorTest {

    @Test
    fun `non-positive MediaStore size is UNKNOWN regardless of stored size`() {
        assertEquals(SizeCheck.UNKNOWN, checkActiveFileSize(null, 0L))
        assertEquals(SizeCheck.UNKNOWN, checkActiveFileSize(100L, 0L))
        assertEquals(SizeCheck.UNKNOWN, checkActiveFileSize(100L, -1L))
    }

    @Test
    fun `missing stored size is BACKFILL`() {
        assertEquals(SizeCheck.BACKFILL, checkActiveFileSize(null, 22868295L))
    }

    @Test
    fun `equal sizes are UNCHANGED`() {
        assertEquals(SizeCheck.UNCHANGED, checkActiveFileSize(22868295L, 22868295L))
    }

    @Test
    fun `different sizes are MODIFIED`() {
        // The travel.info case: 168 bytes appended to the original JPEG.
        assertEquals(SizeCheck.MODIFIED, checkActiveFileSize(22868295L, 22868463L))
        assertEquals(SizeCheck.MODIFIED, checkActiveFileSize(22868463L, 22868295L))
    }
}
