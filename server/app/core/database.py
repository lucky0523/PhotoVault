"""Database connection and session management.

SQLite database initialization, connection pooling, and session lifecycle.

Provides:
- ``init_db`` — Create tables/indexes (idempotent).
- ``get_db`` — FastAPI dependency yielding a configured aiosqlite connection.
- ``startup_db`` / ``shutdown_db`` — App lifespan hooks.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncGenerator
from pathlib import Path

import aiosqlite

from app.core.config import get_settings

logger = logging.getLogger("photovault.database")

# SQL schema definitions
_CREATE_USERS_TABLE = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    is_admin BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
"""

_CREATE_FILE_RECORDS_TABLE = """
CREATE TABLE IF NOT EXISTS file_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    file_hash TEXT NOT NULL,
    file_path TEXT NOT NULL,
    original_path TEXT NOT NULL,
    device_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    mime_type TEXT,
    exif_time TIMESTAMP,
    focal_length REAL,
    is_reference BOOLEAN DEFAULT FALSE,
    reference_to INTEGER,
    live_photo_group_id TEXT,
    live_photo_type TEXT,
    media_type TEXT DEFAULT 'image',
    is_motion_photo BOOLEAN DEFAULT FALSE,
    motion_video_offset INTEGER,
    is_ultra_hdr BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    deleted_batch_id TEXT,
    purged_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (reference_to) REFERENCES file_records(id),
    UNIQUE(user_id, file_hash, file_path)
);
"""

_CREATE_UPLOAD_SESSIONS_TABLE = """
CREATE TABLE IF NOT EXISTS upload_sessions (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    file_hash TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL,
    received_chunks TEXT DEFAULT '[]',
    target_path TEXT NOT NULL,
    device_name TEXT NOT NULL,
    original_path TEXT NOT NULL,
    exif_time TIMESTAMP,
    mime_type TEXT,
    status TEXT DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
"""

_CREATE_INDEXES = [
    "CREATE INDEX IF NOT EXISTS idx_file_hash ON file_records(user_id, file_hash);",
    "CREATE INDEX IF NOT EXISTS idx_upload_session_user ON upload_sessions(user_id, file_hash);",
    "CREATE INDEX IF NOT EXISTS idx_file_records_trash ON file_records(user_id, deleted_at, purged_at);",
]


async def init_db(db_path: str) -> None:
    """Initialize the SQLite database with all required tables and indexes.

    This function is idempotent — calling it multiple times will not cause errors
    or duplicate tables/indexes.

    Args:
        db_path: File path for the SQLite database file.
    """
    async with aiosqlite.connect(db_path) as db:
        # Enable WAL mode for better concurrent read performance
        await db.execute("PRAGMA journal_mode=WAL;")
        # Enable foreign key constraint enforcement
        await db.execute("PRAGMA foreign_keys=ON;")

        # Create tables
        await db.execute(_CREATE_USERS_TABLE)
        await db.execute(_CREATE_FILE_RECORDS_TABLE)
        await db.execute(_CREATE_UPLOAD_SESSIONS_TABLE)

        # Idempotent column migration for existing databases (before indexes)
        cursor = await db.execute("PRAGMA table_info(file_records)")
        existing_cols = {row[1] for row in await cursor.fetchall()}
        if "deleted_at" not in existing_cols:
            await db.execute("ALTER TABLE file_records ADD COLUMN deleted_at TIMESTAMP;")
        if "deleted_batch_id" not in existing_cols:
            await db.execute("ALTER TABLE file_records ADD COLUMN deleted_batch_id TEXT;")
        if "purged_at" not in existing_cols:
            await db.execute("ALTER TABLE file_records ADD COLUMN purged_at TIMESTAMP;")
        if "focal_length" not in existing_cols:
            await db.execute("ALTER TABLE file_records ADD COLUMN focal_length REAL;")
        if "media_type" not in existing_cols:
            await db.execute(
                "ALTER TABLE file_records ADD COLUMN media_type TEXT DEFAULT 'image';"
            )
        if "is_motion_photo" not in existing_cols:
            await db.execute(
                "ALTER TABLE file_records ADD COLUMN is_motion_photo BOOLEAN DEFAULT FALSE;"
            )
        if "motion_video_offset" not in existing_cols:
            await db.execute(
                "ALTER TABLE file_records ADD COLUMN motion_video_offset INTEGER;"
            )
        if "is_ultra_hdr" not in existing_cols:
            await db.execute(
                "ALTER TABLE file_records ADD COLUMN is_ultra_hdr BOOLEAN DEFAULT FALSE;"
            )

        cursor = await db.execute("PRAGMA table_info(upload_sessions)")
        session_cols = {row[1] for row in await cursor.fetchall()}
        if "exif_time" not in session_cols:
            await db.execute("ALTER TABLE upload_sessions ADD COLUMN exif_time TIMESTAMP;")
        if "mime_type" not in session_cols:
            await db.execute("ALTER TABLE upload_sessions ADD COLUMN mime_type TEXT;")

        # Create indexes
        for index_sql in _CREATE_INDEXES:
            await db.execute(index_sql)

        await db.commit()


# ---------------------------------------------------------------------------
# Connection helper
# ---------------------------------------------------------------------------


def _extract_db_path(db_url: str) -> str:
    """Extract database file path from database URL.

    Handles both plain file paths and URLs with prefixes like sqlite:// or sqlite+aiosqlite://.
    """
    if db_url.startswith("sqlite+aiosqlite://"):
        return db_url[len("sqlite+aiosqlite://"):]
    elif db_url.startswith("sqlite://"):
        return db_url[len("sqlite://"):]
    return db_url


async def _create_connection(db_url: str) -> aiosqlite.Connection:
    """Create a new aiosqlite connection with standard pragmas applied.

    Each connection has:
    - ``PRAGMA foreign_keys=ON``
    - ``row_factory`` set to ``aiosqlite.Row`` for dict-like access
    """
    db_path = _extract_db_path(db_url)
    db = await aiosqlite.connect(db_path)
    await db.execute("PRAGMA foreign_keys=ON;")
    db.row_factory = aiosqlite.Row
    return db


# ---------------------------------------------------------------------------
# FastAPI dependency
# ---------------------------------------------------------------------------


async def get_db() -> AsyncGenerator[aiosqlite.Connection, None]:
    """FastAPI dependency that provides a database connection.

    Usage::

        @router.get("/items")
        async def list_items(db: aiosqlite.Connection = Depends(get_db)):
            ...

    The connection is created per-request and closed automatically after use.
    """
    settings = get_settings()
    db = await _create_connection(settings.database_url)
    try:
        yield db
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# Startup / Shutdown hooks
# ---------------------------------------------------------------------------


async def startup_db() -> None:
    """Initialize the database on application startup.

    - Ensures the database file's parent directory exists.
    - Ensures the storage root directory exists.
    - Calls ``init_db`` to create tables/indexes.
    """
    settings = get_settings()
    db_path = _extract_db_path(settings.database_url)

    # Ensure database parent directory exists
    db_dir = Path(db_path).parent
    db_dir.mkdir(parents=True, exist_ok=True)

    # Ensure storage root directory exists
    storage_root = Path(settings.storage_root)
    storage_root.mkdir(parents=True, exist_ok=True)

    logger.info("Initializing database at %s", db_path)
    await init_db(db_path)
    logger.info("Database initialized successfully")


async def shutdown_db() -> None:
    """Clean up database resources on application shutdown.

    Currently a no-op since connections are created per-request and closed
    immediately after use. Provided as a hook for future connection pool
    implementations.
    """
    logger.info("Database shutdown complete")
