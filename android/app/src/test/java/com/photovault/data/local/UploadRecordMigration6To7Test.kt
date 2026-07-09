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
 * Unit test for the Room 6→7 migration ([DatabaseModule.MIGRATION_6_7]).
 *
 * Version 7 adds `folder_uri` and `mime_type` to `upload_records` so an
 * interrupted upload can be rebuilt into a FileInfo and resumed after the
 * process is killed. This test verifies the migration:
 * - adds both new columns,
 * - defaults them to '' for rows that existed before the upgrade,
 * - preserves all existing column data.
 *
 * The DB uses exportSchema = false, so MigrationTestHelper (which needs the
 * exported schema JSON) isn't available. Instead we build the v6 table with
 * the real Room-generated DDL, run the actual migration object, and assert on
 * the resulting schema + data. Runs on Robolectric's SQLite (JVM, no device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UploadRecordMigration6To7Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val existingUri = "content://media/external/video/media/1"

    // The exact v6 DDL Room generated for the upload_records table (before the
    // folder_uri / mime_type columns were added).
    private val createV6UploadRecords = """
        CREATE TABLE IF NOT EXISTS `upload_records` (
            `file_uri` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `file_hash` TEXT NOT NULL,
            `file_name` TEXT NOT NULL,
            `file_size` INTEGER NOT NULL,
            `file_modified_time` INTEGER NOT NULL,
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
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createV6UploadRecords)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // No-op: the test drives the migration explicitly.
                }
            })
            .build()

        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        db = helper.writableDatabase

        // Seed a row as it would exist under schema v6 (no folder_uri/mime_type).
        db.execSQL(
            """
            INSERT INTO upload_records
                (file_uri, session_id, file_hash, file_name, file_size,
                 file_modified_time, total_chunks, uploaded_chunk_index, created_at, updated_at)
            VALUES (?, 'sess-1', 'hash-1', 'holiday.mp4', 123456, 111, 10, 3, 222, 333)
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
    fun `migration adds folder_uri and mime_type columns`() {
        DatabaseModule.MIGRATION_6_7.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`upload_records`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIdx))
            }
        }

        assertTrue("folder_uri column should exist after migration", "folder_uri" in columns)
        assertTrue("mime_type column should exist after migration", "mime_type" in columns)
    }

    @Test
    fun `migration defaults new columns to empty string for existing rows`() {
        DatabaseModule.MIGRATION_6_7.migrate(db)

        db.query(
            "SELECT folder_uri, mime_type FROM upload_records WHERE file_uri = ?",
            arrayOf<Any>(existingUri)
        ).use { cursor ->
            assertTrue("seeded row should still exist", cursor.moveToFirst())
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("folder_uri")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("mime_type")))
        }
    }

    @Test
    fun `migration preserves existing column data`() {
        DatabaseModule.MIGRATION_6_7.migrate(db)

        db.query(
            """
            SELECT session_id, file_hash, file_name, file_size,
                   file_modified_time, total_chunks, uploaded_chunk_index, created_at, updated_at
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
            assertEquals(10, cursor.getInt(cursor.getColumnIndexOrThrow("total_chunks")))
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("uploaded_chunk_index")))
            assertEquals(222L, cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
            assertEquals(333L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }
    }

    @Test
    fun `post-migration rows can persist folder and mime values`() {
        DatabaseModule.MIGRATION_6_7.migrate(db)

        val newUri = "content://media/external/video/media/2"
        db.execSQL(
            """
            INSERT INTO upload_records
                (file_uri, session_id, file_hash, file_name, file_size, file_modified_time,
                 total_chunks, uploaded_chunk_index, created_at, updated_at, folder_uri, mime_type)
            VALUES (?, 'sess-2', 'hash-2', 'clip.mp4', 999, 1, 5, 0, 2, 3,
                    'content://tree/primary%3ADCIM', 'video/mp4')
            """.trimIndent(),
            arrayOf<Any>(newUri)
        )

        db.query(
            "SELECT folder_uri, mime_type FROM upload_records WHERE file_uri = ?",
            arrayOf<Any>(newUri)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "content://tree/primary%3ADCIM",
                cursor.getString(cursor.getColumnIndexOrThrow("folder_uri"))
            )
            assertEquals("video/mp4", cursor.getString(cursor.getColumnIndexOrThrow("mime_type")))
        }
    }

    companion object {
        private const val TEST_DB = "migration-6-7-test.db"
    }
}
