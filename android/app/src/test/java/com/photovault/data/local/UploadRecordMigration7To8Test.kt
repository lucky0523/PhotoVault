package com.photovault.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.photovault.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit test for the Room 7→8 migration ([DatabaseModule.MIGRATION_7_8]).
 *
 * Version 8 adds `pause_source` and `paused_at` to `upload_records` so files
 * left uploading when the user turns off automatic backup can be kept as
 * "paused" tasks (surfaced to the user and ordered by pause time) instead of
 * being resumed silently. Both columns are nullable; NULL means an ordinary
 * resume record. This test verifies the migration:
 * - adds both new columns,
 * - leaves them NULL for rows that existed before the upgrade,
 * - preserves all existing column data.
 *
 * The DB uses exportSchema = false, so MigrationTestHelper (which needs the
 * exported schema JSON) isn't available. Instead we build the v7 table with
 * the real Room-generated DDL, run the actual migration object, and assert on
 * the resulting schema + data. Runs on Robolectric's SQLite (JVM, no device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UploadRecordMigration7To8Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val existingUri = "content://media/external/video/media/1"

    // The exact v7 DDL Room generated for the upload_records table (before the
    // pause_source / paused_at columns were added; folder_uri + mime_type were
    // introduced in v7).
    private val createV7UploadRecords = """
        CREATE TABLE IF NOT EXISTS `upload_records` (
            `file_uri` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `file_hash` TEXT NOT NULL,
            `file_name` TEXT NOT NULL,
            `file_size` INTEGER NOT NULL,
            `file_modified_time` INTEGER NOT NULL,
            `folder_uri` TEXT NOT NULL,
            `mime_type` TEXT NOT NULL,
            `total_chunks` INTEGER NOT NULL,
            `uploaded_chunk_index` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`file_uri`)
        )
    """.trimIndent()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(TEST_DB)

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createV7UploadRecords)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // No-op: the test drives the migration explicitly.
                }
            })
            .build()

        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        db = helper.writableDatabase

        // Seed a row as it would exist under schema v7 (no pause_source/paused_at).
        db.execSQL(
            """
            INSERT INTO upload_records
                (file_uri, session_id, file_hash, file_name, file_size, file_modified_time,
                 folder_uri, mime_type, total_chunks, uploaded_chunk_index, created_at, updated_at)
            VALUES (?, 'sess-1', 'hash-1', 'holiday.mp4', 123456, 111,
                    'content://tree/primary%3ADCIM', 'video/mp4', 10, 3, 222, 333)
            """.trimIndent(),
            arrayOf<Any>(existingUri)
        )
    }

    @After
    fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase(TEST_DB)
    }

    @Test
    fun `migration adds pause_source and paused_at columns`() {
        DatabaseModule.MIGRATION_7_8.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`upload_records`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIdx))
            }
        }

        assertTrue("pause_source column should exist after migration", "pause_source" in columns)
        assertTrue("paused_at column should exist after migration", "paused_at" in columns)
    }

    @Test
    fun `migration leaves new columns NULL for existing rows`() {
        DatabaseModule.MIGRATION_7_8.migrate(db)

        db.query(
            "SELECT pause_source, paused_at FROM upload_records WHERE file_uri = ?",
            arrayOf<Any>(existingUri)
        ).use { cursor ->
            assertTrue("seeded row should still exist", cursor.moveToFirst())
            assertTrue(
                "pause_source should be NULL for pre-migration rows",
                cursor.isNull(cursor.getColumnIndexOrThrow("pause_source"))
            )
            assertTrue(
                "paused_at should be NULL for pre-migration rows",
                cursor.isNull(cursor.getColumnIndexOrThrow("paused_at"))
            )
        }
    }

    @Test
    fun `migration preserves existing column data`() {
        DatabaseModule.MIGRATION_7_8.migrate(db)

        db.query(
            """
            SELECT session_id, file_hash, file_name, file_size, file_modified_time,
                   folder_uri, mime_type, total_chunks, uploaded_chunk_index, created_at, updated_at
            FROM upload_records WHERE file_uri = ?
            """.trimIndent(),
            arrayOf<Any>(existingUri)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sess-1", cursor.getString(cursor.getColumnIndexOrThrow("session_id")))
            assertEquals("hash-1", cursor.getString(cursor.getColumnIndexOrThrow("file_hash")))
            assertEquals("holiday.mp4", cursor.getString(cursor.getColumnIndexOrThrow("file_name")))
            assertEquals(123456L, cursor.getLong(cursor.getColumnIndexOrThrow("file_size")))
            assertEquals(111L, cursor.getLong(cursor.getColumnIndexOrThrow("file_modified_time")))
            assertEquals(
                "content://tree/primary%3ADCIM",
                cursor.getString(cursor.getColumnIndexOrThrow("folder_uri"))
            )
            assertEquals("video/mp4", cursor.getString(cursor.getColumnIndexOrThrow("mime_type")))
            assertEquals(10, cursor.getInt(cursor.getColumnIndexOrThrow("total_chunks")))
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("uploaded_chunk_index")))
            assertEquals(222L, cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
            assertEquals(333L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }
    }

    @Test
    fun `post-migration rows can persist pause_source and paused_at values`() {
        DatabaseModule.MIGRATION_7_8.migrate(db)

        val pausedUri = "content://media/external/video/media/2"
        db.execSQL(
            """
            INSERT INTO upload_records
                (file_uri, session_id, file_hash, file_name, file_size, file_modified_time,
                 folder_uri, mime_type, total_chunks, uploaded_chunk_index, created_at, updated_at,
                 pause_source, paused_at)
            VALUES (?, 'sess-2', 'hash-2', 'clip.mp4', 999, 1,
                    'content://tree/primary%3ADCIM', 'video/mp4', 5, 0, 2, 3,
                    'AUTO_OFF', 44444)
            """.trimIndent(),
            arrayOf<Any>(pausedUri)
        )

        db.query(
            "SELECT pause_source, paused_at FROM upload_records WHERE file_uri = ?",
            arrayOf<Any>(pausedUri)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("AUTO_OFF", cursor.getString(cursor.getColumnIndexOrThrow("pause_source")))
            assertEquals(44444L, cursor.getLong(cursor.getColumnIndexOrThrow("paused_at")))
        }
    }

    companion object {
        private const val TEST_DB = "migration-7-8-test.db"
    }
}
