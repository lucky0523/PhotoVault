"""Tests for the database initialization module."""

import os
import tempfile

import aiosqlite
import pytest

from app.core.database import (
    init_db,
    get_db,
    startup_db,
    shutdown_db,
    _create_connection,
)


@pytest.fixture
def db_path(tmp_path):
    """Provide a temporary database file path."""
    return str(tmp_path / "test.db")


@pytest.fixture
def settings_with_tmp_db(tmp_path, monkeypatch):
    """Configure settings to use a temporary database and storage root."""
    db_path = str(tmp_path / "data" / "photovault.db")
    storage_root = str(tmp_path / "storage")

    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", db_path)
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", storage_root)

    # Reset the settings singleton so it picks up new env vars
    from app.core.config import reset_settings
    reset_settings()
    yield {"db_path": db_path, "storage_root": storage_root}
    reset_settings()


class TestInitDb:
    """Test database initialization."""

    async def test_creates_database_file(self, db_path):
        await init_db(db_path)
        assert os.path.exists(db_path)

    async def test_creates_users_table(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='users';"
            )
            row = await cursor.fetchone()
            assert row is not None
            assert row[0] == "users"

    async def test_creates_file_records_table(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='file_records';"
            )
            row = await cursor.fetchone()
            assert row is not None
            assert row[0] == "file_records"

    async def test_creates_upload_sessions_table(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='upload_sessions';"
            )
            row = await cursor.fetchone()
            assert row is not None
            assert row[0] == "upload_sessions"

    async def test_creates_idx_file_hash_index(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_file_hash';"
            )
            row = await cursor.fetchone()
            assert row is not None
            assert row[0] == "idx_file_hash"

    async def test_creates_idx_upload_session_user_index(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_upload_session_user';"
            )
            row = await cursor.fetchone()
            assert row is not None
            assert row[0] == "idx_upload_session_user"


class TestUsersSchema:
    """Test users table schema matches expected columns."""

    async def test_users_columns(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(users);")
            columns = await cursor.fetchall()
            col_names = [col[1] for col in columns]
            assert col_names == [
                "id",
                "username",
                "password_hash",
                "is_admin",
                "created_at",
            ]

    async def test_users_id_is_primary_key(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(users);")
            columns = await cursor.fetchall()
            # pk column is index 5 in PRAGMA table_info
            id_col = columns[0]
            assert id_col[1] == "id"
            assert id_col[5] == 1  # pk flag

    async def test_users_username_not_null(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(users);")
            columns = await cursor.fetchall()
            username_col = columns[1]
            assert username_col[1] == "username"
            assert username_col[3] == 1  # notnull flag


class TestFileRecordsSchema:
    """Test file_records table schema matches expected columns."""

    async def test_file_records_columns(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(file_records);")
            columns = await cursor.fetchall()
            col_names = [col[1] for col in columns]
            expected = [
                "id",
                "user_id",
                "file_hash",
                "file_path",
                "original_path",
                "device_name",
                "file_size",
                "file_name",
                "mime_type",
                "exif_time",
                "focal_length",
                "is_reference",
                "reference_to",
                "live_photo_group_id",
                "live_photo_type",
                "media_type",
                "is_motion_photo",
                "motion_video_offset",
                "is_ultra_hdr",
                "created_at",
                "deleted_at",
                "deleted_batch_id",
                "purged_at",
            ]
            assert col_names == expected

    async def test_file_records_user_id_not_null(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(file_records);")
            columns = await cursor.fetchall()
            user_id_col = columns[1]
            assert user_id_col[1] == "user_id"
            assert user_id_col[3] == 1  # notnull

    async def test_file_records_has_unique_constraint(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA index_list(file_records);")
            indexes = await cursor.fetchall()
            # Find the unique index (auto-created for UNIQUE constraint)
            unique_indexes = [idx for idx in indexes if idx[2] == 1]  # unique flag
            assert len(unique_indexes) >= 1


class TestUploadSessionsSchema:
    """Test upload_sessions table schema matches expected columns."""

    async def test_upload_sessions_columns(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(upload_sessions);")
            columns = await cursor.fetchall()
            col_names = [col[1] for col in columns]
            expected = [
                "id",
                "user_id",
                "file_hash",
                "file_name",
                "file_size",
                "total_chunks",
                "received_chunks",
                "target_path",
                "device_name",
                "original_path",
                "exif_time",
                "mime_type",
                "status",
                "created_at",
                "updated_at",
                "expires_at",
            ]
            assert col_names == expected

    async def test_upload_sessions_id_is_text_primary_key(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA table_info(upload_sessions);")
            columns = await cursor.fetchall()
            id_col = columns[0]
            assert id_col[1] == "id"
            assert id_col[2] == "TEXT"
            assert id_col[5] == 1  # pk flag


class TestIdempotency:
    """Test that init_db is idempotent."""

    async def test_calling_init_db_twice_does_not_error(self, db_path):
        await init_db(db_path)
        # Second call should not raise
        await init_db(db_path)

    async def test_tables_still_exist_after_second_call(self, db_path):
        await init_db(db_path)
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;"
            )
            tables = [row[0] for row in await cursor.fetchall()]
            assert "users" in tables
            assert "file_records" in tables
            assert "upload_sessions" in tables

    async def test_data_preserved_after_second_init(self, db_path):
        await init_db(db_path)
        # Insert a user
        async with aiosqlite.connect(db_path) as db:
            await db.execute(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                ("testuser", "hash123"),
            )
            await db.commit()

        # Re-init
        await init_db(db_path)

        # Data should still be there
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("SELECT username FROM users;")
            row = await cursor.fetchone()
            assert row[0] == "testuser"


class TestWalMode:
    """Test that WAL mode is enabled."""

    async def test_wal_mode_enabled(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            cursor = await db.execute("PRAGMA journal_mode;")
            row = await cursor.fetchone()
            assert row[0] == "wal"


class TestForeignKeys:
    """Test that foreign key constraints are enforced during init."""

    async def test_foreign_keys_enabled_during_init(self, db_path):
        await init_db(db_path)
        async with aiosqlite.connect(db_path) as db:
            # Enable FK for this connection (each connection needs it)
            await db.execute("PRAGMA foreign_keys=ON;")
            # Try inserting a file_record with non-existent user_id
            with pytest.raises(Exception):
                await db.execute(
                    """INSERT INTO file_records
                    (user_id, file_hash, file_path, original_path, device_name, file_size, file_name)
                    VALUES (999, 'abc', '/path', '/orig', 'device', 100, 'file.jpg')""",
                )
                await db.commit()


# ---------------------------------------------------------------------------
# Tests for connection management (get_db, _create_connection)
# ---------------------------------------------------------------------------


class TestCreateConnection:
    """Test _create_connection helper."""

    async def test_connection_has_foreign_keys_enabled(self, db_path):
        await init_db(db_path)
        db = await _create_connection(db_path)
        try:
            cursor = await db.execute("PRAGMA foreign_keys;")
            row = await cursor.fetchone()
            assert row[0] == 1
        finally:
            await db.close()

    async def test_connection_has_row_factory_set(self, db_path):
        await init_db(db_path)
        db = await _create_connection(db_path)
        try:
            assert db.row_factory is aiosqlite.Row
        finally:
            await db.close()

    async def test_connection_returns_row_objects(self, db_path):
        await init_db(db_path)
        db = await _create_connection(db_path)
        try:
            await db.execute(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                ("alice", "hash"),
            )
            await db.commit()
            cursor = await db.execute("SELECT username, password_hash FROM users;")
            row = await cursor.fetchone()
            # aiosqlite.Row supports index and key access
            assert row["username"] == "alice"
            assert row["password_hash"] == "hash"
        finally:
            await db.close()

    async def test_foreign_key_enforcement_on_connection(self, db_path):
        await init_db(db_path)
        db = await _create_connection(db_path)
        try:
            with pytest.raises(Exception):
                await db.execute(
                    """INSERT INTO file_records
                    (user_id, file_hash, file_path, original_path, device_name, file_size, file_name)
                    VALUES (999, 'abc', '/path', '/orig', 'device', 100, 'file.jpg')"""
                )
                await db.commit()
        finally:
            await db.close()


class TestGetDb:
    """Test get_db FastAPI dependency."""

    async def test_get_db_yields_connection(self, settings_with_tmp_db):
        """get_db should yield a usable aiosqlite connection."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        # Ensure parent directory exists before init_db
        Path(cfg["db_path"]).parent.mkdir(parents=True, exist_ok=True)
        await init_db(cfg["db_path"])

        gen = get_db()
        db = await gen.__anext__()
        try:
            assert db is not None
            # Should be able to execute queries
            cursor = await db.execute("SELECT 1;")
            row = await cursor.fetchone()
            assert row[0] == 1
        finally:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    async def test_get_db_connection_has_foreign_keys(self, settings_with_tmp_db):
        """Connection from get_db should have foreign keys enabled."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        Path(cfg["db_path"]).parent.mkdir(parents=True, exist_ok=True)
        await init_db(cfg["db_path"])

        gen = get_db()
        db = await gen.__anext__()
        try:
            cursor = await db.execute("PRAGMA foreign_keys;")
            row = await cursor.fetchone()
            assert row[0] == 1
        finally:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    async def test_get_db_connection_has_row_factory(self, settings_with_tmp_db):
        """Connection from get_db should have row_factory set."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        Path(cfg["db_path"]).parent.mkdir(parents=True, exist_ok=True)
        await init_db(cfg["db_path"])

        gen = get_db()
        db = await gen.__anext__()
        try:
            assert db.row_factory is aiosqlite.Row
        finally:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    async def test_get_db_closes_connection_after_use(self, settings_with_tmp_db):
        """Connection should be closed after the generator exits."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        Path(cfg["db_path"]).parent.mkdir(parents=True, exist_ok=True)
        await init_db(cfg["db_path"])

        gen = get_db()
        db = await gen.__anext__()
        # Exhaust the generator
        try:
            await gen.__anext__()
        except StopAsyncIteration:
            pass

        # Connection should be closed — attempting to use it should fail
        with pytest.raises(Exception):
            await db.execute("SELECT 1;")


# ---------------------------------------------------------------------------
# Tests for startup_db / shutdown_db
# ---------------------------------------------------------------------------


class TestStartupDb:
    """Test startup_db lifecycle hook."""

    async def test_startup_creates_db_parent_directory(self, settings_with_tmp_db):
        """startup_db should create the database file's parent directory."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        db_dir = Path(cfg["db_path"]).parent
        assert not db_dir.exists()

        await startup_db()

        assert db_dir.exists()

    async def test_startup_creates_storage_root(self, settings_with_tmp_db):
        """startup_db should create the storage root directory."""
        cfg = settings_with_tmp_db
        from pathlib import Path

        storage_dir = Path(cfg["storage_root"])
        assert not storage_dir.exists()

        await startup_db()

        assert storage_dir.exists()

    async def test_startup_initializes_database(self, settings_with_tmp_db):
        """startup_db should create all tables in the database."""
        cfg = settings_with_tmp_db

        await startup_db()

        async with aiosqlite.connect(cfg["db_path"]) as db:
            cursor = await db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;"
            )
            tables = [row[0] for row in await cursor.fetchall()]
            assert "users" in tables
            assert "file_records" in tables
            assert "upload_sessions" in tables

    async def test_startup_is_idempotent(self, settings_with_tmp_db):
        """Calling startup_db multiple times should not error."""
        await startup_db()
        await startup_db()  # Should not raise


class TestShutdownDb:
    """Test shutdown_db lifecycle hook."""

    async def test_shutdown_does_not_error(self, settings_with_tmp_db):
        """shutdown_db should complete without errors."""
        await startup_db()
        await shutdown_db()  # Should not raise
