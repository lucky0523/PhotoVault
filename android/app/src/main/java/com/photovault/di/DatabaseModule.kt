package com.photovault.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.dao.UploadRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS upload_records (
                    file_uri TEXT NOT NULL PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    file_hash TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    file_modified_time INTEGER NOT NULL,
                    total_chunks INTEGER NOT NULL,
                    uploaded_chunk_index INTEGER NOT NULL DEFAULT -1,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS backup_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    file_uri TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    error_message TEXT,
                    folder_uri TEXT NOT NULL,
                    completed_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS photo_status (
                    file_uri TEXT NOT NULL PRIMARY KEY,
                    file_hash TEXT,
                    status TEXT NOT NULL,
                    deleted_at INTEGER,
                    expires_at INTEGER,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backup_folders ADD COLUMN trashed_images INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE backup_folders ADD COLUMN purged_images INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index photo_status.status so status-filtered reads (restore
            // reconciliation) use the index instead of scanning the whole table.
            // Name must match Room's generated index name for the schema hash to validate.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_status_status ON photo_status (status)")
        }
    }

    // Exposed (internal) so the 6→7 migration can be unit-tested directly.
    internal val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Persist folder + MIME on upload_records so an interrupted upload can
            // be rebuilt into a FileInfo and resumed after the process is killed
            // (the in-memory backup queue is lost, but these records survive).
            db.execSQL("ALTER TABLE upload_records ADD COLUMN folder_uri TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE upload_records ADD COLUMN mime_type TEXT NOT NULL DEFAULT ''")
        }
    }

    // Exposed (internal) so the 7→8 migration can be unit-tested directly.
    internal val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Track why an upload is paused so files left uploading when the user
            // turns off automatic backup are kept as "paused" tasks (AUTO_OFF)
            // and ordered by pause time, instead of being resumed silently.
            // Both columns are nullable (NULL = ordinary resume record).
            db.execSQL("ALTER TABLE upload_records ADD COLUMN pause_source TEXT")
            db.execSQL("ALTER TABLE upload_records ADD COLUMN paused_at INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "photovault_db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build()
    }

    @Provides
    @Singleton
    fun provideBackupFolderDao(database: AppDatabase): BackupFolderDao {
        return database.backupFolderDao()
    }

    @Provides
    @Singleton
    fun provideUploadRecordDao(database: AppDatabase): UploadRecordDao {
        return database.uploadRecordDao()
    }

    @Provides
    @Singleton
    fun provideBackupHistoryDao(database: AppDatabase): BackupHistoryDao {
        return database.backupHistoryDao()
    }

    @Provides
    @Singleton
    fun providePhotoStatusDao(database: AppDatabase): PhotoStatusDao {
        return database.photoStatusDao()
    }
}
