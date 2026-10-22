"""Shared setup for the path/thumbnail contract tests.

Files are created through the real producers: ``UploadService.init_upload``
(``StoragePathEngine.resolve_path``), ``ChunkManager.store_chunk`` and
``UploadService.complete_upload`` (``register_or_reactivate_file``), so the
stored ``file_path`` strings are exactly what production writes.

Note: macOS APFS is case-insensitive, so ``Camera`` and ``camera`` share one
physical directory there. Tests use distinct file names per directory and
assert via each record's own ``file_path`` and DB columns.
"""

from __future__ import annotations

import hashlib
import io
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Optional

import aiosqlite
from PIL import Image

from app.core.config import reset_settings
from app.core.database import init_db
from app.models.storage import FileMetadata, StoragePolicy
from app.services.file_browse_service import FileBrowseService
from app.services.storage_path_engine import StoragePathEngine
from app.services.upload_service import InitUploadInfo, UploadService


def jpeg_bytes(seed: int) -> bytes:
    """A small JPEG whose content (and hash) is unique per ``seed``."""
    img = Image.new("RGB", (48, 32), (seed * 37 % 256, seed * 59 % 256, seed * 83 % 256))
    img.putpixel((0, 0), (seed % 256, (seed // 256) % 256, 7))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=95)
    return buf.getvalue()


async def make_library(tmp_path: Path, monkeypatch) -> SimpleNamespace:
    """Temp media root + real schema with users alice (1) and bob (2).

    Users are inserted with SQL: AuthService would hit a local ``.env``
    ``max_users`` limit. The caller closes ``lib.db`` and resets settings.
    """
    base = Path(os.path.realpath(tmp_path))
    media_root = str(base / "media")
    os.makedirs(media_root, exist_ok=True)
    db_path = str(base / "test.db")
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", media_root)
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", db_path)
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-contract-tests")
    reset_settings()
    await init_db(db_path)
    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row
    await db.execute("PRAGMA foreign_keys=ON;")
    await db.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'x')")
    await db.execute("INSERT INTO users (id, username, password_hash) VALUES (2, 'bob', 'x')")
    await db.commit()
    return SimpleNamespace(
        db=db,
        db_path=db_path,
        base=base,
        media_root=media_root,
        browse=FileBrowseService(db, media_root),
    )


def close_presence_fds() -> None:
    """Release the server presence locks taken by complete_upload."""
    from app.services import version_service as vs

    for fd in vs._presence_fds.values():
        try:
            os.close(fd)
        except OSError:
            pass
    vs._presence_fds.clear()


async def upload(
    lib: SimpleNamespace,
    user_id: int,
    username: str,
    *,
    device: str,
    source_folder: str,
    name: str,
    seed: Optional[int] = None,
    content: Optional[bytes] = None,
    custom_path: Optional[str] = None,
) -> dict:
    """Upload through the real flow; returns the resulting file_records row."""
    data = content if content is not None else jpeg_bytes(seed)
    policy = StoragePolicy(use_custom_path=custom_path is not None, custom_path=custom_path)
    info = InitUploadInfo(
        file_hash=hashlib.sha256(data).hexdigest(),
        file_name=name,
        file_size=len(data),
        file_path=f"/storage/emulated/0/{source_folder}/{name}",
        device_name=device,
        source_folder=source_folder,
        storage_policy=policy,
        file_metadata=FileMetadata(),
        mime_type="image/jpeg",
    )
    service = UploadService(lib.db, lib.media_root)
    init = await service.init_upload(user_id=user_id, username=username, file_info=info)
    assert not init.is_duplicate, "contract uploads must have unique content"
    size = service._chunk_manager.chunk_size
    for index, start in enumerate(range(0, len(data), size)):
        chunk = data[start:start + size]
        await service._chunk_manager.store_chunk(
            init.session_id, index, chunk, hashlib.md5(chunk).hexdigest()
        )
    result = await service.complete_upload(init.session_id, user_id=user_id)
    assert result.success, result.error
    row = await fetch(lib, result.file_id)
    expected_dir = StoragePathEngine.resolve_path(
        lib.media_root, username, device, source_folder, policy, FileMetadata()
    )
    assert row["file_path"] == expected_dir + name
    return row


async def fetch(lib: SimpleNamespace, file_id: int) -> dict:
    cursor = await lib.db.execute("SELECT * FROM file_records WHERE id = ?", (file_id,))
    return dict(await cursor.fetchone())


async def backdate_deleted(lib: SimpleNamespace, file_id: int, days: int = 60) -> None:
    """Make a trashed record look older than the retention period."""
    from datetime import datetime, timedelta, timezone

    old = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
    await lib.db.execute("UPDATE file_records SET deleted_at = ? WHERE id = ?", (old, file_id))
    await lib.db.commit()
