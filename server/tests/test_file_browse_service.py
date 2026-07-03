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
    cache_path = Path(storage_root) / ".thumbnails" / "alice" / "hash_a1_small.jpg"
    assert cache_path.exists()

    # Second call should return cached version
    thumb2 = await service.get_thumbnail(user_id=1, file_id=1, size="small")
    assert thumb2 == thumb1


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
