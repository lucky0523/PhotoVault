"""Tests for FileBrowseService and file browsing API endpoints."""

import os
import tempfile
from pathlib import Path

import aiosqlite
import pytest
import pytest_asyncio

from app.services.file_browse_service import FileBrowseService


@pytest_asyncio.fixture
async def temp_storage():
    """Create a temporary storage directory."""
    with tempfile.TemporaryDirectory() as tmpdir:
        yield tmpdir


@pytest_asyncio.fixture
async def db_connection(temp_storage):
    """Create an in-memory database with schema and test data."""
    db = await aiosqlite.connect(":memory:")
    db.row_factory = aiosqlite.Row
    await db.execute("PRAGMA foreign_keys=ON;")

    # Create tables
    await db.execute("""
        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            is_admin BOOLEAN DEFAULT FALSE,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    await db.execute("""
        CREATE TABLE file_records (
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
            FOREIGN KEY (reference_to) REFERENCES file_records(id)
        )
    """)
    await db.commit()

    # Insert test users
    await db.execute(
        "INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'hash1')"
    )
    await db.execute(
        "INSERT INTO users (id, username, password_hash) VALUES (2, 'bob', 'hash2')"
    )
    await db.commit()

    yield db
    await db.close()


@pytest_asyncio.fixture
async def service_with_data(db_connection, temp_storage):
    """Create a FileBrowseService with test data populated."""
    db = db_connection
    storage_root = temp_storage

    # Create file structure for alice
    alice_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
    alice_dir.mkdir(parents=True, exist_ok=True)

    # Create actual image files (small JPEG for thumbnail testing)
    from PIL import Image
    import io

    def create_test_image(path: str, width: int = 100, height: int = 100):
        img = Image.new("RGB", (width, height), color="red")
        img.save(path, "JPEG")

    img1_path = str(alice_dir / "photo1.jpg")
    img2_path = str(alice_dir / "photo2.jpg")
    create_test_image(img1_path)
    create_test_image(img2_path)

    # Create a subdirectory with files
    burst_dir = alice_dir / "burst"
    burst_dir.mkdir(parents=True, exist_ok=True)
    img3_path = str(burst_dir / "burst1.jpg")
    create_test_image(img3_path)

    # Insert file records for alice
    await db.execute(
        """INSERT INTO file_records
           (id, user_id, file_hash, file_path, original_path, device_name,
            file_size, file_name, mime_type, exif_time, media_type)
           VALUES (1, 1, 'hash_a1', ?, '/DCIM/Camera/photo1.jpg', 'Pixel9Pro',
                   1024, 'photo1.jpg', 'image/jpeg', '2026-03-15 10:00:00', 'image')""",
        (img1_path,),
    )
    await db.execute(
        """INSERT INTO file_records
           (id, user_id, file_hash, file_path, original_path, device_name,
            file_size, file_name, mime_type, exif_time, media_type)
           VALUES (2, 1, 'hash_a2', ?, '/DCIM/Camera/photo2.jpg', 'Pixel9Pro',
                   2048, 'photo2.jpg', 'image/jpeg', '2026-03-16 11:00:00', 'image')""",
        (img2_path,),
    )
    await db.execute(
        """INSERT INTO file_records
           (id, user_id, file_hash, file_path, original_path, device_name,
            file_size, file_name, mime_type, exif_time, media_type)
           VALUES (3, 1, 'hash_a3', ?, '/DCIM/Camera/burst/burst1.jpg', 'Pixel9Pro',
                   512, 'burst1.jpg', 'image/jpeg', '2026-03-17 12:00:00', 'image')""",
        (img3_path,),
    )

    # Insert file records for bob (to test user isolation)
    bob_dir = Path(storage_root) / "bob" / "iPhone15" / "Photos"
    bob_dir.mkdir(parents=True, exist_ok=True)
    bob_img_path = str(bob_dir / "bob_photo.jpg")
    create_test_image(bob_img_path)

    await db.execute(
        """INSERT INTO file_records
           (id, user_id, file_hash, file_path, original_path, device_name,
            file_size, file_name, mime_type, media_type)
           VALUES (4, 2, 'hash_b1', ?, '/Photos/bob_photo.jpg', 'iPhone15',
                   4096, 'bob_photo.jpg', 'image/jpeg', 'image')""",
        (bob_img_path,),
    )
    await db.commit()

    service = FileBrowseService(db, storage_root)
    return service, db, storage_root


# ---------------------------------------------------------------------------
# Tests for list_directory
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_list_directory_root(service_with_data):
    """Test listing root directory shows device names."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(user_id=1, path="")

    assert listing.current_path == ""
    assert listing.parent_path is None
    # Root should show device directory "Pixel9Pro"
    assert len(listing.directories) == 1
    assert listing.directories[0].name == "Pixel9Pro"
    assert listing.directories[0].file_count == 3  # All files under Pixel9Pro


@pytest.mark.asyncio
async def test_list_directory_device_level(service_with_data):
    """Test listing device directory shows source folders."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(user_id=1, path="Pixel9Pro")

    assert listing.current_path == "Pixel9Pro"
    assert listing.parent_path == ""
    # Should show DCIM directory
    assert len(listing.directories) == 1
    assert listing.directories[0].name == "DCIM"


@pytest.mark.asyncio
async def test_list_directory_with_files(service_with_data):
    """Test listing a directory that contains files directly."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(
        user_id=1, path="Pixel9Pro/DCIM/Camera"
    )

    assert listing.current_path == "Pixel9Pro/DCIM/Camera"
    assert listing.parent_path == "Pixel9Pro/DCIM"
    # Should have burst subdirectory
    assert len(listing.directories) == 1
    assert listing.directories[0].name == "burst"
    assert listing.directories[0].file_count == 1
    # Should have 2 files directly in Camera
    assert listing.total_files == 2
    assert len(listing.files) == 2
    file_names = {f.file_name for f in listing.files}
    assert "photo1.jpg" in file_names
    assert "photo2.jpg" in file_names


@pytest.mark.asyncio
async def test_list_directory_pagination(service_with_data):
    """Test pagination of file listing."""
    service, db, storage_root = service_with_data

    # Page 1 with page_size=1
    listing = await service.list_directory(
        user_id=1, path="Pixel9Pro/DCIM/Camera", page=1, page_size=1
    )
    assert listing.total_files == 2
    assert len(listing.files) == 1
    assert listing.page == 1
    assert listing.page_size == 1

    # Page 2 with page_size=1
    listing2 = await service.list_directory(
        user_id=1, path="Pixel9Pro/DCIM/Camera", page=2, page_size=1
    )
    assert listing2.total_files == 2
    assert len(listing2.files) == 1
    assert listing2.page == 2

    # The two pages should have different files
    assert listing.files[0].id != listing2.files[0].id


@pytest.mark.asyncio
async def test_list_directory_counts_trashed_after_relocation(service_with_data):
    """Trashing a file relocates its file_path under .trash/, but it must still
    be counted in its ORIGINAL directory's trashed_count (regression test).

    Reproduces the reported bug: a folder with 3 photos where one is moved to
    the recycle bin showed "回收站 0" because the aggregation matched the raw
    file_path against the original directory prefix, which no longer matched
    once the file was relocated to the .trash mirror.
    """
    service, db, storage_root = service_with_data

    # Simulate moving photo1 (id=1) to the recycle bin exactly like
    # _move_to_trash does: set deleted_at and rewrite file_path to the
    # {base}/.trash/{device}/{rel} mirror location.
    trashed_path = str(
        Path(storage_root) / "alice" / ".trash" / "Pixel9Pro" / "DCIM" / "Camera" / "photo1.jpg"
    )
    await db.execute(
        "UPDATE file_records SET deleted_at = '2026-03-18 09:00:00', file_path = ? WHERE id = 1",
        (trashed_path,),
    )
    await db.commit()

    # At root, the device folder aggregates 2 backed_up + 1 trashed = 3 total.
    root_listing = await service.list_directory(user_id=1, path="")
    device_dirs = {d.name: d for d in root_listing.directories}
    assert "Pixel9Pro" in device_dirs
    # The .trash mirror must NOT surface as its own directory.
    assert ".trash" not in device_dirs
    pixel = device_dirs["Pixel9Pro"]
    assert pixel.file_count == 3
    assert pixel.backed_up_count == 2
    assert pixel.trashed_count == 1
    assert pixel.purged_count == 0

    # Drilling into Pixel9Pro/DCIM, the Camera subdir reflects the same split.
    dcim_listing = await service.list_directory(user_id=1, path="Pixel9Pro/DCIM")
    camera = {d.name: d for d in dcim_listing.directories}["Camera"]
    assert camera.backed_up_count == 2
    assert camera.trashed_count == 1

    # The trashed file must not appear in the direct file list of its folder.
    camera_listing = await service.list_directory(user_id=1, path="Pixel9Pro/DCIM/Camera")
    assert camera_listing.total_files == 1
    assert {f.file_name for f in camera_listing.files} == {"photo2.jpg"}


@pytest.mark.asyncio
async def test_list_directory_user_isolation(service_with_data):
    """Test that users can only see their own files."""
    service, db, storage_root = service_with_data

    # Alice should not see Bob's files
    alice_listing = await service.list_directory(user_id=1, path="")
    alice_dirs = {d.name for d in alice_listing.directories}
    assert "iPhone15" not in alice_dirs

    # Bob should not see Alice's files
    bob_listing = await service.list_directory(user_id=2, path="")
    bob_dirs = {d.name for d in bob_listing.directories}
    assert "Pixel9Pro" not in bob_dirs
    assert "iPhone15" in bob_dirs


@pytest.mark.asyncio
async def test_list_directory_sort_by_time(service_with_data):
    """Test sorting files by time."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(
        user_id=1, path="Pixel9Pro/DCIM/Camera", sort_by="time"
    )

    # Should be sorted by time descending
    assert len(listing.files) == 2
    assert listing.files[0].file_name == "photo2.jpg"  # newer
    assert listing.files[1].file_name == "photo1.jpg"  # older


@pytest.mark.asyncio
async def test_list_directory_sort_by_size(service_with_data):
    """Test sorting files by size."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(
        user_id=1, path="Pixel9Pro/DCIM/Camera", sort_by="size"
    )

    # Should be sorted by size descending
    assert len(listing.files) == 2
    assert listing.files[0].file_name == "photo2.jpg"  # 2048 bytes
    assert listing.files[1].file_name == "photo1.jpg"  # 1024 bytes


# ---------------------------------------------------------------------------
# Tests for get_file_info
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_file_info_success(service_with_data):
    """Test getting file info for own file."""
    service, db, storage_root = service_with_data

    info = await service.get_file_info(user_id=1, file_id=1)

    assert info is not None
    assert info.id == 1
    assert info.file_name == "photo1.jpg"
    assert info.file_size == 1024
    assert info.mime_type == "image/jpeg"
    assert info.device_name == "Pixel9Pro"


@pytest.mark.asyncio
async def test_get_file_info_user_isolation(service_with_data):
    """Test that user cannot access another user's file info."""
    service, db, storage_root = service_with_data

    # Alice trying to access Bob's file
    info = await service.get_file_info(user_id=1, file_id=4)
    assert info is None

    # Bob trying to access Alice's file
    info = await service.get_file_info(user_id=2, file_id=1)
    assert info is None


@pytest.mark.asyncio
async def test_get_file_info_nonexistent(service_with_data):
    """Test getting info for a non-existent file."""
    service, db, storage_root = service_with_data

    info = await service.get_file_info(user_id=1, file_id=999)
    assert info is None


# ---------------------------------------------------------------------------
# Tests for get_thumbnail
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_thumbnail_small(service_with_data):
    """Test generating a small thumbnail."""
    service, db, storage_root = service_with_data

    thumbnail = await service.get_thumbnail(user_id=1, file_id=1, size="small")

    assert thumbnail is not None
    assert len(thumbnail) > 0
    # Verify it's a JPEG (starts with FF D8)
    assert thumbnail[:2] == b"\xff\xd8"


@pytest.mark.asyncio
async def test_get_thumbnail_medium(service_with_data):
    """Test generating a medium thumbnail."""
    service, db, storage_root = service_with_data

    thumbnail = await service.get_thumbnail(user_id=1, file_id=1, size="medium")

    assert thumbnail is not None
    assert len(thumbnail) > 0
    assert thumbnail[:2] == b"\xff\xd8"


@pytest.mark.asyncio
async def test_get_thumbnail_caching(service_with_data):
    """Test that thumbnails are cached after first generation."""
    service, db, storage_root = service_with_data

    # First call generates the thumbnail
    thumb1 = await service.get_thumbnail(user_id=1, file_id=1, size="small")
    assert thumb1 is not None

    # Verify cache file exists
    cache_path = (
        Path(storage_root)
        / ".thumbnails"
        / "alice"
        / f"hash_a1_small_{service.THUMBNAIL_CACHE_VERSION}.jpg"
    )
    assert cache_path.exists()

    # Second call should return cached version
    thumb2 = await service.get_thumbnail(user_id=1, file_id=1, size="small")
    assert thumb2 == thumb1


@pytest.mark.asyncio
async def test_get_thumbnail_cache_hit_refreshes_mtime(service_with_data):
    """A cache hit marks the thumbnail as used for the cleanup task (throttled)."""
    import time

    service, db, storage_root = service_with_data
    assert await service.get_thumbnail(user_id=1, file_id=1, size="small") is not None
    cache_path = (
        Path(storage_root)
        / ".thumbnails"
        / "alice"
        / f"hash_a1_small_{service.THUMBNAIL_CACHE_VERSION}.jpg"
    )

    # Touched within the throttle interval: left as is.
    recent = time.time() - 600
    os.utime(cache_path, (recent, recent))
    await service.get_thumbnail(user_id=1, file_id=1, size="small")
    assert cache_path.stat().st_mtime == pytest.approx(recent, abs=1)

    # Older than the interval: refreshed to now.
    old = time.time() - 40 * 86400
    os.utime(cache_path, (old, old))
    before = time.time()
    thumb = await service.get_thumbnail(user_id=1, file_id=1, size="small")
    assert thumb is not None
    assert cache_path.stat().st_mtime >= before - 1


@pytest.mark.asyncio
async def test_get_thumbnail_user_isolation(service_with_data):
    """Test that user cannot get thumbnail for another user's file."""
    service, db, storage_root = service_with_data

    # Alice trying to get Bob's thumbnail
    thumbnail = await service.get_thumbnail(user_id=1, file_id=4, size="small")
    assert thumbnail is None


@pytest.mark.asyncio
async def test_get_thumbnail_nonexistent_file(service_with_data):
    """Test getting thumbnail for non-existent file."""
    service, db, storage_root = service_with_data

    thumbnail = await service.get_thumbnail(user_id=1, file_id=999, size="small")
    assert thumbnail is None


# ---------------------------------------------------------------------------
# Tests for get_original_stream
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_original_stream_success(service_with_data):
    """Test streaming an original file."""
    service, db, storage_root = service_with_data

    result = await service.get_original_stream(user_id=1, file_id=1)

    assert result is not None
    stream_fn, file_name, file_size, mime_type = result
    assert file_name == "photo1.jpg"
    assert file_size > 0
    assert mime_type == "image/jpeg"

    # Read the stream
    data = b""
    async for chunk in stream_fn():
        data += chunk
    assert len(data) == file_size


@pytest.mark.asyncio
async def test_get_original_stream_user_isolation(service_with_data):
    """Test that user cannot stream another user's file."""
    service, db, storage_root = service_with_data

    # Alice trying to download Bob's file
    result = await service.get_original_stream(user_id=1, file_id=4)
    assert result is None


@pytest.mark.asyncio
async def test_get_original_stream_nonexistent(service_with_data):
    """Test streaming a non-existent file."""
    service, db, storage_root = service_with_data

    result = await service.get_original_stream(user_id=1, file_id=999)
    assert result is None


# ---------------------------------------------------------------------------
# Tests for directory listing edge cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_list_directory_empty_path_normalization(service_with_data):
    """Test that paths with leading/trailing slashes are normalized."""
    service, db, storage_root = service_with_data

    listing1 = await service.list_directory(user_id=1, path="/Pixel9Pro/")
    listing2 = await service.list_directory(user_id=1, path="Pixel9Pro")

    assert listing1.current_path == listing2.current_path
    assert len(listing1.directories) == len(listing2.directories)


@pytest.mark.asyncio
async def test_list_directory_nonexistent_path(service_with_data):
    """Test listing a path that doesn't exist returns empty results."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(user_id=1, path="NonExistentDevice")

    assert listing.current_path == "NonExistentDevice"
    assert len(listing.directories) == 0
    assert len(listing.files) == 0
    assert listing.total_files == 0


@pytest.mark.asyncio
async def test_list_directory_latest_file_time(service_with_data):
    """Test that directory info includes latest file time."""
    service, db, storage_root = service_with_data

    listing = await service.list_directory(user_id=1, path="")

    # Pixel9Pro directory should have latest_file_time from the newest file
    pixel_dir = listing.directories[0]
    assert pixel_dir.name == "Pixel9Pro"
    assert pixel_dir.latest_file_time is not None


# ---------------------------------------------------------------------------
# fcntl lock hardening and post-lock re-read (file versioning, design 5.3 step 2)
# ---------------------------------------------------------------------------


def _open_fd_count() -> int:
    return len(os.listdir("/dev/fd"))


def test_failed_flock_does_not_leak_fds(temp_storage):
    holder = FileBrowseService(None, temp_storage)
    other = FileBrowseService(None, temp_storage)
    assert holder.acquire_file_lock("alice", "h1")
    before = _open_fd_count()
    for _ in range(20):
        assert other.acquire_file_lock("alice", "h1") is False
    assert _open_fd_count() == before
    holder.release_file_lock("alice", "h1")
    assert other.acquire_file_lock("alice", "h1")
    other.release_file_lock("alice", "h1")


def test_lock_on_stale_inode_is_retried(temp_storage, monkeypatch):
    import fcntl as fcntl_module

    service = FileBrowseService(None, temp_storage)
    lock_path = service._get_lock_path("alice", "h1")
    Path(lock_path).touch()
    real_flock = fcntl_module.flock
    calls = {"ex": 0}

    def flaky_flock(fd, op):
        if op & fcntl_module.LOCK_EX and calls["ex"] == 0:
            # Another holder released (unlinked) the path after we opened it.
            os.unlink(lock_path)
            Path(lock_path).touch()
        if op & fcntl_module.LOCK_EX:
            calls["ex"] += 1
        return real_flock(fd, op)

    monkeypatch.setattr(fcntl_module, "flock", flaky_flock)
    fd = service._acquire_lock("alice", "h1")
    assert fd is not None
    assert calls["ex"] == 2
    assert os.fstat(fd).st_ino == os.stat(lock_path).st_ino
    monkeypatch.setattr(fcntl_module, "flock", real_flock)
    service.release_file_lock("alice", "h1")


def test_release_unlinks_before_unlocking(temp_storage, monkeypatch):
    import fcntl as fcntl_module

    service = FileBrowseService(None, temp_storage)
    assert service.acquire_file_lock("alice", "h1")
    events: list[str] = []
    real_unlink, real_flock, real_close = os.unlink, fcntl_module.flock, os.close

    def rec_unlink(path, *a, **k):
        events.append("unlink")
        return real_unlink(path, *a, **k)

    def rec_flock(fd, op):
        if op == fcntl_module.LOCK_UN:
            events.append("unlock")
        return real_flock(fd, op)

    def rec_close(fd):
        events.append("close")
        return real_close(fd)

    monkeypatch.setattr(os, "unlink", rec_unlink)
    monkeypatch.setattr(fcntl_module, "flock", rec_flock)
    monkeypatch.setattr(os, "close", rec_close)
    service.release_file_lock("alice", "h1")
    monkeypatch.undo()
    assert events == ["unlink", "unlock", "close"]
    assert not os.path.exists(service._get_lock_path("alice", "h1"))


@pytest.mark.asyncio
async def test_delete_file_rereads_hash_after_locking(service_with_data, monkeypatch):
    service, db, storage_root = service_with_data
    cursor = await db.execute("SELECT file_path FROM file_records WHERE id = 1")
    original_path = (await cursor.fetchone())["file_path"]
    real_get_username = service._get_username

    async def replaced_meanwhile(user_id):
        # An in-place replacement commits between reading the hash and locking.
        await db.execute("UPDATE file_records SET file_hash = 'hash_new' WHERE id = 1")
        await db.commit()
        return await real_get_username(user_id)

    monkeypatch.setattr(service, "_get_username", replaced_meanwhile)
    ok, message = await service.delete_file(user_id=1, file_id=1)

    assert (ok, message) == (False, "File is currently being processed")
    cursor = await db.execute("SELECT deleted_at, file_path FROM file_records WHERE id = 1")
    row = await cursor.fetchone()
    assert row["deleted_at"] is None
    assert row["file_path"] == original_path
    assert os.path.exists(original_path)
    # The lock was released.
    assert FileBrowseService(db, storage_root).acquire_file_lock("alice", "hash_a1")


@pytest.mark.asyncio
async def test_delete_directory_skips_moving_a_changed_file(service_with_data, monkeypatch):
    service, db, storage_root = service_with_data
    cursor = await db.execute("SELECT file_path FROM file_records WHERE id IN (1, 2) ORDER BY id")
    path1, path2 = [r["file_path"] for r in await cursor.fetchall()]
    real_still_active = service._still_active_with

    async def still_active(user_id, file_id, file_hash, file_path):
        if file_id == 1:
            # Another worker replaced record 1 after the directory was read.
            await db.execute("UPDATE file_records SET file_hash = 'hash_new' WHERE id = 1")
            await db.commit()
        return await real_still_active(user_id, file_id, file_hash, file_path)

    monkeypatch.setattr(service, "_still_active_with", still_active)
    count, _ = await service.delete_directory(user_id=1, path="Pixel9Pro/DCIM/Camera")

    assert count == 3
    cursor = await db.execute("SELECT id, deleted_at, file_path FROM file_records WHERE id IN (1, 2) ORDER BY id")
    rows = await cursor.fetchall()
    assert all(r["deleted_at"] is not None for r in rows)
    assert rows[0]["file_path"] == path1 and os.path.exists(path1)  # not moved
    assert rows[1]["file_path"] != path2 and "/.trash/" in rows[1]["file_path"]
    assert not os.path.exists(path2)


# ---------------------------------------------------------------------------
# File versioning (design sections 8 and 9): isolation, purge cascade and the
# status-sync correction. Real schema via init_db (file_versions exists).
# ---------------------------------------------------------------------------

import hashlib  # noqa: E402
from dataclasses import asdict  # noqa: E402

from app.core.database import init_db  # noqa: E402

V_OLD = b"\xff\xd8 version one of the picture " * 16
V_NEW = b"\xff\xd8 version two of the picture " * 16  # same length as V_OLD


def _h(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@pytest_asyncio.fixture
async def versioned(tmp_path):
    """init_db schema, alice (id 1), four Pixel9Pro records: R and A active,
    T trashed, P purged."""
    from app.services import version_service as vs

    db_path = str(tmp_path / "pv.db")
    media_root = str(tmp_path / "media")
    await init_db(db_path)
    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row
    await db.execute("PRAGMA foreign_keys=ON;")
    await db.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'x')")
    camera = Path(media_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
    camera.mkdir(parents=True)
    ids = {}
    for key, name, content, extra in (
        ("R", "IMG_1.jpg", V_OLD, {}),
        ("A", "IMG_2.jpg", b"another photo", {}),
        ("T", "IMG_3.jpg", b"trashed photo", {"deleted_at": "2026-10-01T00:00:00+00:00"}),
        ("P", "IMG_4.jpg", b"purged photo", {"deleted_at": "2026-09-01T00:00:00+00:00",
                                             "purged_at": "2026-10-01T00:00:00+00:00"}),
    ):
        path = camera / name
        path.write_bytes(content)
        values = {
            "user_id": 1, "file_hash": _h(content), "file_path": str(path),
            "original_path": f"content://media/{key}", "device_name": "Pixel9Pro",
            "file_size": len(content), "file_name": name, "mime_type": "image/jpeg",
            "created_at": "2026-10-01 00:00:00", **extra,
        }
        cursor = await db.execute(
            f"INSERT INTO file_records ({', '.join(values)}) "
            f"VALUES ({', '.join('?' for _ in values)})",
            tuple(values.values()),
        )
        ids[key] = cursor.lastrowid
    await db.commit()
    try:
        yield db, media_root, ids
    finally:
        for fd in vs._presence_fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        vs._presence_fds.clear()
        await db.close()


async def _replace_r(db, media_root, record_id, content=V_NEW):
    from app.services.version_service import VersionService

    cursor = await db.execute("SELECT * FROM file_records WHERE id = ?", (record_id,))
    record = await cursor.fetchone()
    incoming = Path(media_root) / ".chunks" / "s" / "merged-000000000000.jpg"
    incoming.parent.mkdir(parents=True, exist_ok=True)
    incoming.write_bytes(content)
    return await VersionService(db, media_root, role="server").replace_record_content(
        op="upload", username="alice", record=record, incoming_path=str(incoming),
        new_hash=_h(content), new_size=len(content), content_values={}, media_meta=None,
    )


async def _snapshot(service):
    listings = {}
    for path in ("", "Pixel9Pro", "Pixel9Pro/DCIM", "Pixel9Pro/DCIM/Camera"):
        listings[path] = asdict(await service.list_directory(1, path))
    trash, trash_total = await service.list_trash(1)
    return {
        "devices": [asdict(d) for d in await service.get_device_stats(1)],
        "listings": listings,
        "all": asdict(await service.list_all_files(1)),
        "trash": ([asdict(t) for t in trash], trash_total),
        "status": sorted(
            (asdict(s) for s in await service.get_status_changes(1)),
            key=lambda s: s["file_hash"],
        ),
    }


@pytest.mark.asyncio
async def test_replacement_changes_no_listing_or_count(versioned):
    """Acceptance 10: stats, tree counts, listings, trash and status-sync are
    identical before and after a replacement (same size, so even byte totals),
    and status-sync never mentions the history hash."""
    db, media_root, ids = versioned
    service = FileBrowseService(db, media_root)
    before = await _snapshot(service)
    await _replace_r(db, media_root, ids["R"])
    cursor = await db.execute("SELECT file_hash FROM file_versions")
    assert [r["file_hash"] for r in await cursor.fetchall()] == [_h(V_OLD)]
    after = await _snapshot(service)
    assert after == before
    device = after["devices"][0]
    assert (device["backed_up_count"], device["trashed_count"], device["purged_count"]) == (2, 1, 1)
    assert after["all"]["total_files"] == 2 and after["trash"][1] == 1
    assert _h(V_OLD) not in {s["file_hash"] for s in after["status"]}
    assert _h(V_NEW) not in {s["file_hash"] for s in after["status"]}


@pytest.mark.asyncio
async def test_purge_cascades_to_versions(versioned):
    db, media_root, ids = versioned
    service = FileBrowseService(db, media_root)
    outcome = await _replace_r(db, media_root, ids["R"])
    version_file = Path(outcome.version_path)
    assert version_file.read_bytes() == V_OLD
    assert (await service.delete_file(1, ids["R"]))[0]
    ok, _ = await service.purge_file(1, ids["R"])
    assert ok
    cursor = await db.execute("SELECT COUNT(*) FROM file_versions")
    assert (await cursor.fetchone())[0] == 0
    assert not version_file.exists() and not version_file.parent.exists()
    versions_root = Path(os.path.realpath(media_root)) / ".versions"
    assert (versions_root / ".server-presence.lock").exists()


@pytest.mark.asyncio
async def test_purge_with_promotion_cascades_to_versions(versioned):
    """The promotion branch: the versions belong to the purged original."""
    db, media_root, ids = versioned
    service = FileBrowseService(db, media_root)
    cursor = await db.execute("SELECT * FROM file_records WHERE id = ?", (ids["T"],))
    original = await cursor.fetchone()
    await db.execute(
        """INSERT INTO file_records (user_id, file_hash, file_path, original_path, device_name,
           file_size, file_name, is_reference, reference_to)
           VALUES (1, ?, ?, 'content://ref', 'Pixel9Pro', 1, 'ref.jpg', 1, ?)""",
        (original["file_hash"], original["file_path"] + ".ref.jpg", ids["T"]),
    )
    version_dir = Path(os.path.realpath(media_root)) / ".versions" / str(ids["T"])
    version_dir.mkdir(parents=True)
    version_file = version_dir / "20261001T000000Z_aaaaaaaa.jpg"
    version_file.write_bytes(b"older")
    await db.execute(
        """INSERT INTO file_versions (file_record_id, user_id, file_hash, file_size,
           stored_path, replaced_at, expires_at)
           VALUES (?, 1, 'aaaaaaaa', 5, ?, '2026-10-01T00:00:00+00:00',
                   '2026-10-31T00:00:00+00:00')""",
        (ids["T"], str(version_file)),
    )
    await db.commit()
    ok, message = await service.purge_file(1, ids["T"])
    assert ok and "ownership transferred" in message
    cursor = await db.execute("SELECT COUNT(*) FROM file_versions")
    assert (await cursor.fetchone())[0] == 0
    assert not version_file.exists()


@pytest.mark.asyncio
async def test_status_changes_skip_hashes_that_are_still_active(versioned):
    """A hash on any active record is active: a trashed _1 with R's new hash
    must not make the client mark R's file as trashed."""
    db, media_root, ids = versioned
    service = FileBrowseService(db, media_root)
    cursor = await db.execute("SELECT file_hash FROM file_records WHERE id = ?", (ids["A"],))
    active_hash = (await cursor.fetchone())["file_hash"]
    await db.execute(
        """INSERT INTO file_records (user_id, file_hash, file_path, original_path, device_name,
           file_size, file_name, deleted_at)
           VALUES (1, ?, '/m/alice/Pixel9Pro/IMG_2_1.jpg', 'content://x', 'Pixel9Pro', 1,
                   'IMG_2_1.jpg', '2026-10-02T00:00:00+00:00')""",
        (active_hash,),
    )
    await db.commit()
    hashes = {s.file_hash: s.status for s in await service.get_status_changes(1)}
    assert active_hash not in hashes
    assert hashes == {_h(b"trashed photo"): "trashed", _h(b"purged photo"): "purged"}


def test_display_path_for_file_directly_under_user_root(temp_storage):
    """F6: a file with no device directory (custom path == user root) used to
    raise UnboundLocalError, turning list_trash into a 500."""
    service = FileBrowseService(None, temp_storage)
    root = temp_storage
    assert service._get_display_path(f"{root}/alice/x.jpg", "alice") == "/x.jpg"
    assert service._get_display_path(f"{root}/alice/.trash/x.jpg", "alice") == "/x.jpg"
    # Unchanged for the usual device layout, live and trashed.
    assert (
        service._get_display_path(f"{root}/alice/Pixel9Pro/DCIM/a.jpg", "alice")
        == "/Pixel9Pro/DCIM/a.jpg"
    )
    assert (
        service._get_display_path(f"{root}/alice/.trash/Pixel9Pro/DCIM/a.jpg", "alice")
        == "/Pixel9Pro/DCIM/a.jpg"
    )
    # Trashed custom-path file: its original absolute path.
    assert (
        service._get_display_path(f"{root}/alice/.trash/.external/srv/lib/a.jpg", "alice")
        == "/srv/lib/a.jpg"
    )
