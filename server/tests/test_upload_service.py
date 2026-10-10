"""Tests for UploadService.

Tests cover:
- UploadService.init_upload() — dedup check, path resolution, disk space, session creation
- UploadService.complete_upload() — merge, verify, conflict resolution, move, register
- UploadService.check_disk_space() — disk space verification
- Filename conflict resolution — same content skip, different content suffix
- Directory auto-creation — recursive directory creation
- Error handling — disk space, directory creation failures
"""

import hashlib
import io
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import aiosqlite
from PIL import Image
from PIL.ExifTags import IFD
from PIL.TiffImagePlugin import IFDRational

from app.core.config import reset_settings
from app.core.database import init_db
from app.models.storage import FileMetadata, StoragePolicy
from app.services.auth_service import AuthService
from app.services.chunk_manager import ChunkManager
from app.services.deduplication_service import DeduplicationService
from app.services import media_metadata
from app.services.upload_service import (
    CompleteUploadResult,
    DirectoryCreationError,
    DiskSpaceError,
    InitUploadInfo,
    InitUploadResult,
    UploadService,
)


@pytest.fixture(autouse=True)
def _reset_settings():
    """Reset settings singleton before each test."""
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def db(tmp_path):
    """Create a temp database with schema initialized."""
    storage_root = str(tmp_path / "storage")
    os.makedirs(storage_root, exist_ok=True)

    os.environ["PHOTOVAULT_STORAGE_ROOT"] = storage_root
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-upload-tests"

    reset_settings()

    db_path = str(tmp_path / "test.db")
    await init_db(db_path)

    conn = await aiosqlite.connect(db_path)
    await conn.execute("PRAGMA foreign_keys=ON;")
    conn.row_factory = aiosqlite.Row
    try:
        yield conn
    finally:
        await conn.close()

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


@pytest.fixture
async def seeded_db(db):
    """Database with a test user created."""
    auth_service = AuthService(db)
    await auth_service.create_user("alice", "password123", is_admin=False)
    return db


@pytest.fixture
def storage_root(tmp_path):
    """Return the storage root path."""
    root = str(tmp_path / "storage")
    os.makedirs(root, exist_ok=True)
    return root


@pytest.fixture
def upload_service(seeded_db, storage_root):
    """Create an UploadService instance."""
    return UploadService(seeded_db, storage_root)


def _make_file_info(
    file_hash: str = "abc123hash",
    file_name: str = "photo.jpg",
    file_size: int = 4 * 1024 * 1024,
    file_path: str = "/DCIM/Camera/photo.jpg",
    device_name: str = "Pixel9Pro",
    source_folder: str = "DCIM/Camera",
    use_custom_path: bool = False,
    custom_path: str | None = None,
    use_year_month_layer: bool = False,
) -> InitUploadInfo:
    """Helper to create InitUploadInfo."""
    return InitUploadInfo(
        file_hash=file_hash,
        file_name=file_name,
        file_size=file_size,
        file_path=file_path,
        device_name=device_name,
        source_folder=source_folder,
        storage_policy=StoragePolicy(
            use_custom_path=use_custom_path,
            custom_path=custom_path,
            use_year_month_layer=use_year_month_layer,
        ),
        file_metadata=FileMetadata(),
    )


# ---------------------------------------------------------------------------
# Tests for init_upload
# ---------------------------------------------------------------------------


class TestInitUpload:
    """Tests for UploadService.init_upload()."""

    @pytest.mark.asyncio
    async def test_init_upload_creates_session(self, upload_service):
        """init_upload creates a session and returns session info."""
        file_info = _make_file_info()

        result = await upload_service.init_upload(
            user_id=1,
            username="alice",
            file_info=file_info,
        )

        assert result.is_duplicate is False
        assert result.session_id is not None
        assert result.session_id != ""
        assert result.total_chunks == 2  # 4MB / 2MB = 2 chunks
        assert result.chunk_size == ChunkManager.CHUNK_SIZE

    @pytest.mark.asyncio
    async def test_init_upload_detects_duplicate(self, seeded_db, storage_root):
        """init_upload returns is_duplicate=True when file hash already exists."""
        service = UploadService(seeded_db, storage_root)

        # Register a file first
        dedup = DeduplicationService(seeded_db)
        await dedup.register_file(
            user_id=1,
            file_hash="existing_hash",
            file_path="/storage/alice/device/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1024,
            file_name="photo.jpg",
        )

        file_info = _make_file_info(file_hash="existing_hash")

        result = await service.init_upload(
            user_id=1,
            username="alice",
            file_info=file_info,
        )

        assert result.is_duplicate is True
        assert result.file_id is not None
        assert result.session_id is None

    @pytest.mark.asyncio
    async def test_init_upload_creates_target_directory(
        self, upload_service, storage_root
    ):
        """init_upload creates the target directory if it doesn't exist."""
        file_info = _make_file_info(source_folder="DCIM/Camera/burst")

        result = await upload_service.init_upload(
            user_id=1,
            username="alice",
            file_info=file_info,
        )

        assert result.is_duplicate is False
        # Verify directory was created
        expected_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera" / "burst"
        assert expected_dir.exists()

    @pytest.mark.asyncio
    async def test_init_upload_disk_space_check(self, seeded_db, storage_root):
        """init_upload raises DiskSpaceError when disk space is insufficient."""
        service = UploadService(seeded_db, storage_root)

        # Mock disk_usage to return very low free space
        with patch("app.services.upload_service.shutil.disk_usage") as mock_usage:
            mock_usage.return_value = type(
                "Usage", (), {"total": 1000, "used": 999, "free": 1}
            )()

            file_info = _make_file_info(file_size=1024 * 1024 * 1024)  # 1GB

            with pytest.raises(DiskSpaceError) as exc_info:
                await service.init_upload(
                    user_id=1,
                    username="alice",
                    file_info=file_info,
                )

            assert exc_info.value.available == 1

    @pytest.mark.asyncio
    async def test_init_upload_resolves_path_correctly(
        self, upload_service, storage_root
    ):
        """init_upload resolves the target path based on storage policy."""
        file_info = _make_file_info(source_folder="DCIM/Camera")

        result = await upload_service.init_upload(
            user_id=1,
            username="alice",
            file_info=file_info,
        )

        assert result.target_path is not None
        assert "alice" in result.target_path
        assert "Pixel9Pro" in result.target_path
        assert "DCIM/Camera" in result.target_path

    @pytest.mark.asyncio
    async def test_init_upload_with_custom_path(self, upload_service, storage_root):
        """init_upload uses custom path when specified in policy."""
        custom = str(Path(storage_root) / "custom" / "travel")
        file_info = _make_file_info(
            source_folder="DCIM/Camera",
            use_custom_path=True,
            custom_path=custom,
        )

        result = await upload_service.init_upload(
            user_id=1,
            username="alice",
            file_info=file_info,
        )

        assert result.target_path is not None
        assert "custom/travel" in result.target_path


# ---------------------------------------------------------------------------
# Tests for complete_upload
# ---------------------------------------------------------------------------


class TestCompleteUpload:
    """Tests for UploadService.complete_upload()."""

    async def _setup_session_with_chunks(
        self,
        db,
        storage_root: str,
        user_id: int = 1,
        file_content: bytes = b"Hello, World! This is test file content.",
        file_name: str = "test_photo.jpg",
        target_subdir: str = "alice/Pixel9Pro/DCIM/Camera",
        exif_time: datetime | None = None,
    ) -> tuple[str, str]:
        """Helper to create a session and store all chunks.

        Returns (session_id, file_hash).
        """
        file_hash = hashlib.sha256(file_content).hexdigest()
        file_size = len(file_content)

        target_path = str(Path(storage_root) / target_subdir)
        os.makedirs(target_path, exist_ok=True)

        chunk_manager = ChunkManager(db, storage_root)
        session_id = await chunk_manager.create_session(
            user_id=user_id,
            file_hash=file_hash,
            file_name=file_name,
            file_size=file_size,
            target_path=target_path,
            device_name="Pixel9Pro",
            original_path=f"/DCIM/Camera/{file_name}",
            exif_time=exif_time,
        )

        # Store chunks
        chunk_size = ChunkManager.CHUNK_SIZE
        import math

        total_chunks = math.ceil(file_size / chunk_size)
        for i in range(total_chunks):
            start = i * chunk_size
            end = min(start + chunk_size, file_size)
            chunk_data = file_content[start:end]
            md5 = hashlib.md5(chunk_data).hexdigest()
            await chunk_manager.store_chunk(session_id, i, chunk_data, md5)

        return session_id, file_hash

    @pytest.mark.asyncio
    async def test_complete_upload_success(self, seeded_db, storage_root):
        """complete_upload merges chunks and registers file successfully."""
        service = UploadService(seeded_db, storage_root)
        content = b"Test file content for complete upload"

        session_id, file_hash = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=content
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert result.file_id is not None
        assert result.stored_path != ""
        assert Path(result.stored_path).exists()

        # Verify file content
        stored_content = Path(result.stored_path).read_bytes()
        assert stored_content == content

    @pytest.mark.asyncio
    async def test_complete_upload_session_not_found(self, seeded_db, storage_root):
        """complete_upload returns error when session doesn't exist."""
        service = UploadService(seeded_db, storage_root)

        result = await service.complete_upload(
            session_id="nonexistent-session-id", user_id=1
        )

        assert result.success is False
        assert "not found" in result.error.lower()

    @pytest.mark.asyncio
    async def test_complete_upload_wrong_user(self, seeded_db, storage_root):
        """complete_upload returns error when session belongs to different user."""
        service = UploadService(seeded_db, storage_root)
        content = b"User isolation test content"

        session_id, _ = await self._setup_session_with_chunks(
            seeded_db, storage_root, user_id=1, file_content=content
        )

        # Try to complete as user 2
        result = await service.complete_upload(session_id=session_id, user_id=2)

        assert result.success is False
        assert "not found" in result.error.lower()

    @pytest.mark.asyncio
    async def test_complete_upload_integrity_failure(self, seeded_db, storage_root):
        """complete_upload returns error when integrity check fails."""
        service = UploadService(seeded_db, storage_root)

        # Create session with wrong hash
        chunk_manager = ChunkManager(seeded_db, storage_root)
        target_path = str(Path(storage_root) / "alice" / "device" / "DCIM")
        os.makedirs(target_path, exist_ok=True)

        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="wrong_hash_value",
            file_name="bad.jpg",
            file_size=10,
            target_path=target_path,
            device_name="Pixel9Pro",
            original_path="/DCIM/bad.jpg",
        )

        # Store chunk with actual data
        data = b"0123456789"
        md5 = hashlib.md5(data).hexdigest()
        await chunk_manager.store_chunk(session_id, 0, data, md5)

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is False
        assert "integrity" in result.error.lower()

    @pytest.mark.asyncio
    async def test_complete_upload_same_content_skip(self, seeded_db, storage_root):
        """complete_upload skips storage when target file has same content."""
        service = UploadService(seeded_db, storage_root)
        content = b"Duplicate content test data"
        file_hash = hashlib.sha256(content).hexdigest()

        target_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
        target_dir.mkdir(parents=True, exist_ok=True)

        # Pre-create the file with same content
        existing_file = target_dir / "duplicate.jpg"
        existing_file.write_bytes(content)

        # Create session pointing to same target
        chunk_manager = ChunkManager(seeded_db, storage_root)
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash=file_hash,
            file_name="duplicate.jpg",
            file_size=len(content),
            target_path=str(target_dir),
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/duplicate.jpg",
        )

        # Store chunk
        md5 = hashlib.md5(content).hexdigest()
        await chunk_manager.store_chunk(session_id, 0, content, md5)

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert result.is_duplicate is True
        assert result.stored_path == str(existing_file)

    @pytest.mark.asyncio
    async def test_complete_upload_different_content_suffix(
        self, seeded_db, storage_root
    ):
        """complete_upload appends _1 suffix when target has different content."""
        service = UploadService(seeded_db, storage_root)
        new_content = b"New different content for conflict test"
        old_content = b"Old existing content that differs"

        target_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
        target_dir.mkdir(parents=True, exist_ok=True)

        # Pre-create the file with different content
        existing_file = target_dir / "conflict.jpg"
        existing_file.write_bytes(old_content)

        # Setup session with new content
        session_id, file_hash = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=new_content,
            file_name="conflict.jpg",
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert result.stored_path.endswith("conflict_1.jpg")
        assert Path(result.stored_path).exists()
        assert Path(result.stored_path).read_bytes() == new_content
        # Original file should still exist
        assert existing_file.exists()
        assert existing_file.read_bytes() == old_content

    @pytest.mark.asyncio
    async def test_complete_upload_multiple_conflicts(self, seeded_db, storage_root):
        """complete_upload increments suffix when multiple conflicts exist."""
        service = UploadService(seeded_db, storage_root)
        new_content = b"Yet another version of the file"

        target_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
        target_dir.mkdir(parents=True, exist_ok=True)

        # Pre-create files with different content
        (target_dir / "multi.jpg").write_bytes(b"version 0")
        (target_dir / "multi_1.jpg").write_bytes(b"version 1")

        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=new_content,
            file_name="multi.jpg",
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert result.stored_path.endswith("multi_2.jpg")
        assert Path(result.stored_path).read_bytes() == new_content

    @pytest.mark.asyncio
    async def test_complete_upload_registers_file_record(
        self, seeded_db, storage_root
    ):
        """complete_upload registers the file in the database."""
        service = UploadService(seeded_db, storage_root)
        content = b"File to register in database"

        session_id, file_hash = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=content
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True

        # Verify file record exists in database
        dedup = DeduplicationService(seeded_db)
        record = await dedup.check(user_id=1, file_hash=file_hash)
        assert record is not None
        assert record.file_hash == file_hash
        assert record.user_id == 1

    @pytest.mark.asyncio
    async def test_complete_upload_cleans_up_chunks(self, seeded_db, storage_root):
        """complete_upload removes chunk directory after success."""
        service = UploadService(seeded_db, storage_root)
        content = b"Cleanup test content"

        session_id, _ = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=content
        )

        # Verify chunk dir exists before completion
        chunk_dir = Path(storage_root) / ".chunks" / session_id
        assert chunk_dir.exists()

        await service.complete_upload(session_id=session_id, user_id=1)

        # Chunk dir should be cleaned up
        assert not chunk_dir.exists()

    # -- Statistics metadata (design §4.6; AC25–27, AC29) --------------------

    @staticmethod
    async def _file_row(db, file_id: int) -> dict:
        cursor = await db.execute("SELECT * FROM file_records WHERE id = ?", (file_id,))
        return dict(await cursor.fetchone())

    async def test_jpeg_upload_records_metadata(self, seeded_db, storage_root):
        """AC25: a camera JPEG fills every photo column, meta_version=1."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=_exif_jpeg_bytes(),
            file_name="camera.jpg",
            exif_time=datetime(2020, 1, 1, 0, 0, 0),
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["iso"] == 800
        assert row["f_number"] == 2.8
        assert row["exposure_time"] == pytest.approx(1 / 2000)
        assert row["camera_make"] == "Canon"
        assert row["camera_model"] == "Canon EOS R5"
        assert row["lens_model"] == "RF24-70mm F2.8 L IS USM"
        assert row["focal_35mm"] == 50.0
        assert row["focal_35mm_source"] == "exif"
        assert row["focal_actual"] == 35.0
        # The file's own EXIF time wins over the client value.
        assert row["capture_time"] == "2024-05-01T18:30:12"
        assert row["capture_time_kind"] == "local"
        assert row["meta_version"] == 1
        assert row["meta_status"] == "ok"
        assert row["meta_scanned_at"]

    @pytest.mark.parametrize(
        "exif_ifd",
        [
            {41989: 50, 37386: IFDRational(35, 1)},
            {37386: IFDRational(35, 1)},
        ],
        ids=["with-35mm-tag", "actual-only"],
    )
    async def test_legacy_focal_length_unchanged(self, seeded_db, storage_root, exif_ifd):
        """AC29: the old focal_length column still follows _extract_focal_length."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=_exif_jpeg_bytes(exif_ifd=exif_ifd),
            file_name="focal.jpg",
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        row = await self._file_row(seeded_db, result.file_id)
        assert row["focal_length"] == UploadService._extract_focal_length(result.stored_path)
        assert row["focal_length"] is not None

    async def test_video_without_ffprobe(self, seeded_db, storage_root):
        """AC26: upload succeeds, duration unknown, meta_status='no_ffprobe'."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=b"fake mp4 payload",
            file_name="clip.mp4",
            exif_time=datetime(2025, 1, 15, 15, 30, tzinfo=timezone.utc),
        )

        media_metadata._reset_ffprobe_cache()
        try:
            with patch("app.services.media_metadata.shutil.which", return_value=None):
                result = await service.complete_upload(session_id=session_id, user_id=1)
        finally:
            media_metadata._reset_ffprobe_cache()

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["media_type"] == "video"
        assert row["duration_sec"] is None
        assert row["meta_status"] == "no_ffprobe"
        assert row["meta_version"] == 1
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"

    @pytest.mark.skipif(
        shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
        reason="ffmpeg/ffprobe not installed",
    )
    async def test_video_duration_with_ffprobe(self, seeded_db, storage_root, tmp_path):
        clip = tmp_path / "src.mp4"
        subprocess.run(
            [
                "ffmpeg", "-v", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=duration=3:size=64x48:rate=10", "-c:v", "mpeg4",
                "-metadata", "creation_time=2025-01-15T15:30:00Z", str(clip),
            ],
            check=True,
            capture_output=True,
            timeout=60,
        )
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=clip.read_bytes(), file_name="real.mp4"
        )

        media_metadata._reset_ffprobe_cache()
        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["duration_sec"] == pytest.approx(3.0, abs=0.1)
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"
        assert row["meta_status"] == "ok"

    async def test_image_extractor_failure_falls_back(self, seeded_db, storage_root):
        """AC27(a): extract_image_metadata raising -> 'error', client time kept."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=_exif_jpeg_bytes(),
            file_name="fail_a.jpg",
            exif_time=datetime(2025, 1, 15, 15, 30, tzinfo=timezone.utc),
        )

        with patch(
            "app.services.media_metadata.extract_image_metadata",
            side_effect=RuntimeError("boom"),
        ):
            result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["meta_status"] == "error"
        assert row["meta_version"] == 1
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "local"
        assert row["iso"] is None

    async def test_extract_media_metadata_raising_falls_back(self, seeded_db, storage_root):
        """AC27(b): even the never-raise entry point raising keeps the upload."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=_exif_jpeg_bytes(),
            file_name="fail_b.jpg",
            exif_time=datetime(2025, 1, 15, 15, 30, tzinfo=timezone.utc),
        )

        with patch(
            "app.services.upload_service.extract_media_metadata",
            side_effect=RuntimeError("boom"),
        ):
            result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["meta_status"] == "error"
        assert row["meta_version"] == 1
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "local"

    async def test_ffprobe_timeout_keeps_row_pending(self, seeded_db, storage_root):
        """Upload ffprobe timeout: 'timeout', meta_version NULL, timeout=5."""
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db,
            storage_root,
            file_content=b"slow video payload",
            file_name="slow.mp4",
            exif_time=datetime(2025, 1, 15, 15, 30, tzinfo=timezone.utc),
        )

        run = MagicMock(side_effect=subprocess.TimeoutExpired(cmd="ffprobe", timeout=5))
        with patch(
            "app.services.media_metadata.ffprobe_path", return_value="/fake/ffprobe"
        ), patch("app.services.media_metadata.subprocess.run", run):
            result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert run.call_args.kwargs["timeout"] == 5
        row = await self._file_row(seeded_db, result.file_id)
        assert row["meta_status"] == "timeout"
        assert row["meta_version"] is None
        assert row["duration_sec"] is None
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"

    async def test_metadata_write_failure_does_not_fail_upload(self, seeded_db, storage_root):
        service = UploadService(seeded_db, storage_root)
        session_id, _ = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=_exif_jpeg_bytes(), file_name="w.jpg"
        )

        with patch(
            "app.services.upload_service.apply_media_metadata",
            side_effect=aiosqlite.OperationalError("database is locked"),
        ):
            result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["meta_version"] is None

    async def test_same_content_branch_records_metadata(self, seeded_db, storage_root):
        """The same-content reuse branch writes the metadata columns too."""
        service = UploadService(seeded_db, storage_root)
        content = _exif_jpeg_bytes()
        target_dir = Path(storage_root) / "alice" / "Pixel9Pro" / "DCIM" / "Camera"
        target_dir.mkdir(parents=True, exist_ok=True)
        (target_dir / "same.jpg").write_bytes(content)

        session_id, _ = await self._setup_session_with_chunks(
            seeded_db, storage_root, file_content=content, file_name="same.jpg"
        )

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is True
        assert result.is_duplicate is True
        row = await self._file_row(seeded_db, result.file_id)
        assert row["iso"] == 800
        assert row["camera_model"] == "Canon EOS R5"
        assert row["meta_version"] == 1
        assert row["meta_status"] == "ok"


def _exif_jpeg_bytes(exif_ifd: dict | None = None) -> bytes:
    """A small JPEG with camera EXIF (root Make/Model + Exif IFD tags)."""
    img = Image.new("RGB", (64, 48), "blue")
    exif = Image.Exif()
    exif[271] = "Canon"
    exif[272] = "Canon EOS R5"
    ifd = exif.get_ifd(IFD.Exif)
    tags = exif_ifd if exif_ifd is not None else {
        34855: 800,
        33437: IFDRational(28, 10),
        33434: IFDRational(1, 2000),
        36867: "2024:05:01 18:30:12",
        37386: IFDRational(35, 1),
        41989: 50,
        42036: "RF24-70mm F2.8 L IS USM",
    }
    for tag, value in tags.items():
        ifd[tag] = value
    buf = io.BytesIO()
    img.save(buf, format="JPEG", exif=exif.tobytes())
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Tests for check_disk_space
# ---------------------------------------------------------------------------


class TestCheckDiskSpace:
    """Tests for UploadService.check_disk_space()."""

    @pytest.mark.asyncio
    async def test_check_disk_space_sufficient(self, seeded_db, storage_root):
        """check_disk_space returns True when enough space is available."""
        service = UploadService(seeded_db, storage_root)

        # Real disk should have enough space for a small requirement
        assert service.check_disk_space(1024) is True

    @pytest.mark.asyncio
    async def test_check_disk_space_insufficient(self, seeded_db, storage_root):
        """check_disk_space returns False when space is insufficient."""
        service = UploadService(seeded_db, storage_root)

        with patch("app.services.upload_service.shutil.disk_usage") as mock_usage:
            mock_usage.return_value = type(
                "Usage", (), {"total": 1000, "used": 900, "free": 100}
            )()

            assert service.check_disk_space(200) is False

    @pytest.mark.asyncio
    async def test_check_disk_space_os_error(self, seeded_db):
        """check_disk_space returns False when path doesn't exist."""
        service = UploadService(seeded_db, "/nonexistent/path/that/does/not/exist")

        assert service.check_disk_space(1024) is False


# ---------------------------------------------------------------------------
# Tests for directory auto-creation
# ---------------------------------------------------------------------------


class TestDirectoryCreation:
    """Tests for directory auto-creation in UploadService."""

    @pytest.mark.asyncio
    async def test_ensure_directory_creates_nested(self, upload_service, storage_root):
        """_ensure_directory creates deeply nested directories."""
        deep_path = str(
            Path(storage_root) / "a" / "b" / "c" / "d" / "e"
        ) + "/"
        upload_service._ensure_directory(deep_path)

        assert Path(deep_path.rstrip("/")).exists()

    @pytest.mark.asyncio
    async def test_ensure_directory_existing_is_ok(self, upload_service, storage_root):
        """_ensure_directory doesn't fail if directory already exists."""
        existing = str(Path(storage_root)) + "/"
        # Should not raise
        upload_service._ensure_directory(existing)

    @pytest.mark.asyncio
    async def test_ensure_directory_permission_error(self, upload_service):
        """_ensure_directory raises DirectoryCreationError on permission failure."""
        with patch("pathlib.Path.mkdir", side_effect=PermissionError("denied")):
            with pytest.raises(DirectoryCreationError) as exc_info:
                upload_service._ensure_directory("/some/path/")

            assert "Permission denied" in str(exc_info.value)


# ---------------------------------------------------------------------------
# Tests for filename conflict resolution
# ---------------------------------------------------------------------------


class TestFilenameConflictResolution:
    """Tests for _resolve_filename_conflict static method."""

    def test_resolve_conflict_appends_1(self, tmp_path):
        """First conflict gets _1 suffix."""
        existing = tmp_path / "photo.jpg"
        existing.write_bytes(b"existing")

        result = UploadService._resolve_filename_conflict(existing)

        assert result == tmp_path / "photo_1.jpg"

    def test_resolve_conflict_increments(self, tmp_path):
        """Subsequent conflicts increment the suffix."""
        (tmp_path / "photo.jpg").write_bytes(b"v0")
        (tmp_path / "photo_1.jpg").write_bytes(b"v1")
        (tmp_path / "photo_2.jpg").write_bytes(b"v2")

        result = UploadService._resolve_filename_conflict(tmp_path / "photo.jpg")

        assert result == tmp_path / "photo_3.jpg"

    def test_resolve_conflict_preserves_extension(self, tmp_path):
        """Conflict resolution preserves the file extension."""
        existing = tmp_path / "image.heic"
        existing.write_bytes(b"data")

        result = UploadService._resolve_filename_conflict(existing)

        assert result.suffix == ".heic"
        assert result.stem == "image_1"

    def test_resolve_conflict_no_extension(self, tmp_path):
        """Conflict resolution works with files without extension."""
        existing = tmp_path / "README"
        existing.write_bytes(b"data")

        result = UploadService._resolve_filename_conflict(existing)

        assert result == tmp_path / "README_1"


# ---------------------------------------------------------------------------
# Tests for error handling
# ---------------------------------------------------------------------------


class TestErrorHandling:
    """Tests for error handling scenarios."""

    @pytest.mark.asyncio
    async def test_disk_space_error_has_details(self, seeded_db, storage_root):
        """DiskSpaceError includes required and available space."""
        service = UploadService(seeded_db, storage_root)

        with patch("app.services.upload_service.shutil.disk_usage") as mock_usage:
            mock_usage.return_value = type(
                "Usage", (), {"total": 1000, "used": 999, "free": 50}
            )()

            file_info = _make_file_info(file_size=500 * 1024 * 1024)  # 500MB

            with pytest.raises(DiskSpaceError) as exc_info:
                await service.init_upload(
                    user_id=1,
                    username="alice",
                    file_info=file_info,
                )

            err = exc_info.value
            assert err.available == 50
            assert err.required > 0

    @pytest.mark.asyncio
    async def test_directory_creation_error_has_details(self, seeded_db, storage_root):
        """DirectoryCreationError includes path and reason."""
        service = UploadService(seeded_db, storage_root)

        with patch("pathlib.Path.mkdir", side_effect=PermissionError("access denied")):
            file_info = _make_file_info()

            with pytest.raises(DirectoryCreationError) as exc_info:
                await service.init_upload(
                    user_id=1,
                    username="alice",
                    file_info=file_info,
                )

            err = exc_info.value
            assert err.path != ""
            assert "Permission denied" in err.reason

    @pytest.mark.asyncio
    async def test_complete_upload_missing_chunks(self, seeded_db, storage_root):
        """complete_upload returns error when not all chunks are received."""
        service = UploadService(seeded_db, storage_root)

        # Create session but don't upload all chunks
        chunk_manager = ChunkManager(seeded_db, storage_root)
        target_path = str(Path(storage_root) / "alice" / "device" / "DCIM")
        os.makedirs(target_path, exist_ok=True)

        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="some_hash",
            file_name="incomplete.jpg",
            file_size=5 * 1024 * 1024,  # 5MB = 3 chunks
            target_path=target_path,
            device_name="Pixel9Pro",
            original_path="/DCIM/incomplete.jpg",
        )

        # Only upload 1 of 3 chunks
        data = b"x" * ChunkManager.CHUNK_SIZE
        md5 = hashlib.md5(data).hexdigest()
        await chunk_manager.store_chunk(session_id, 0, data, md5)

        result = await service.complete_upload(session_id=session_id, user_id=1)

        assert result.success is False
        assert "Not all chunks received" in result.error
