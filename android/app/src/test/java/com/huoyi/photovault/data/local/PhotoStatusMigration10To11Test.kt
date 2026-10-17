package com.huoyi.photovault.data.local

import android.annotation.SuppressLint
import androidx.room.ColumnInfo
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.huoyi.photovault.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit test for the Room 10→11 migration ([DatabaseModule.MIGRATION_10_11]).
 *
 * Version 11 adds a nullable `file_size` column to `photo_status` so the scan
 * can detect active files that were modified in place (MediaStore SIZE changed)
 * and re-upload them. Rows from before the upgrade keep NULL and are back-filled
 * by the next scan.
 *
 * exportSchema = false, so MigrationTestHelper isn't available. Like
 * [UploadRecordMigration7To8Test], we build the v10 table with the real
 * Room-generated DDL, run the migration object, and assert on schema + data.
 * The TableInfo cases additionally check what Room itself validates when it
 * opens an upgraded database (type affinity, notNull, default value, indices),
 * which `PRAGMA table_info` name checks alone would miss.
 */
@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PhotoStatusMigration10To11Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val existingUri = "content://media/external/images/media/42"

    // The exact v10 DDL Room generated for photo_status, plus its status index.
    private val createV10PhotoStatus = """
        CREATE TABLE IF NOT EXISTS `photo_status` (
            `file_uri` TEXT NOT NULL,
            `file_hash` TEXT,
            `status` TEXT NOT NULL,
            `deleted_at` INTEGER,
            `expires_at` INTEGER,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`file_uri`)
        )
    """.trimIndent()
    private val createV10StatusIndex =
        "CREATE INDEX IF NOT EXISTS `index_photo_status_status` ON `photo_status` (`status`)"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(TEST_DB)

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createV10PhotoStatus)
                    db.execSQL(createV10StatusIndex)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // No-op: the test drives the migration explicitly.
                }
            })
            .build()

        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        db = helper.writableDatabase

        db.execSQL(
            """
            INSERT INTO photo_status (file_uri, file_hash, status, deleted_at, expires_at, updated_at)
            VALUES (?, 'hash-42', 'trashed', 111, 222, 333)
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
    fun `migration adds file_size column`() {
        DatabaseModule.MIGRATION_10_11.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`photo_status`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIdx))
        }

        assertTrue("file_size column should exist after migration", "file_size" in columns)
    }

    @Test
    fun `migration leaves file_size NULL and preserves other columns`() {
        DatabaseModule.MIGRATION_10_11.migrate(db)

        db.query(
            "SELECT file_hash, status, deleted_at, expires_at, updated_at, file_size FROM photo_status WHERE file_uri = ?",
            arrayOf<Any>(existingUri)
        ).use { cursor ->
            assertTrue("seeded row should still exist", cursor.moveToFirst())
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("file_size")))
            assertEquals("hash-42", cursor.getString(cursor.getColumnIndexOrThrow("file_hash")))
            assertEquals("trashed", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(111L, cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")))
            assertEquals(222L, cursor.getLong(cursor.getColumnIndexOrThrow("expires_at")))
            assertEquals(333L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }
    }

    @Test
    fun `migrated table matches Room TableInfo for file_size and keeps status index`() {
        DatabaseModule.MIGRATION_10_11.migrate(db)

        val info = TableInfo.read(db, "photo_status")
        val column = info.columns["file_size"]
        assertNotNull("file_size must be visible to Room's TableInfo", column)
        column!!
        assertEquals("INTEGER", column.type)
        assertEquals(ColumnInfo.INTEGER, column.affinity)
        assertFalse(column.notNull)
        assertNull(column.defaultValue)

        val indexNames = info.indices.orEmpty().map { it.name }
        assertTrue(
            "index_photo_status_status must survive the migration",
            "index_photo_status_status" in indexNames
        )
    }

    @Test
    fun `migrated table equals the table Room creates for v11`() {
        DatabaseModule.MIGRATION_10_11.migrate(db)
        val migrated = TableInfo.read(db, "photo_status")

        // What Room expects: the table it creates itself from the v11 entity.
        val fresh = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val expected = TableInfo.read(fresh.openHelper.writableDatabase, "photo_status")
            assertEquals(expected, migrated)
        } finally {
            fresh.close()
        }
    }

    companion object {
        private const val TEST_DB = "migration-10-11-test.db"
    }
}
