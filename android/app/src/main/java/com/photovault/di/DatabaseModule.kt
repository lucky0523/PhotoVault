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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "photovault_db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
