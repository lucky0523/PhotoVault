"""File versioning: admin cleanup and storage statistics (design 9 and 11).

- DELETE /api/v1/admin/users/{id}/purged-records removes leftover versions
  first (rows in the transaction, files after the commit).
- GET /api/v1/server/about reports versions_count / versions_bytes to admins
  (every existing row, expired or not, acceptance 22); non-admins get no
  storage block at all.

Kept out of test_api_admin.py on purpose: a failing case there leaks
PHOTOVAULT_MAX_USERS=1 into later tests. These fixtures need two users, so
they drop that variable before creating them.
"""

from __future__ import annotations

import os
from pathlib import Path

import aiosqlite
import pytest
from httpx import ASGITransport, AsyncClient

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService


@pytest.fixture
async def env(tmp_path, monkeypatch):
    from app.services import version_service as vs

    storage_root = tmp_path / "storage"
    storage_root.mkdir()
    db_path = tmp_path / "test.db"
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(storage_root))
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", str(db_path))
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-version-admin-tests")
    monkeypatch.delenv("PHOTOVAULT_MEDIA_ROOT", raising=False)
    monkeypatch.delenv("PHOTOVAULT_MAX_USERS", raising=False)
    monkeypatch.delenv("PHOTOVAULT_REQUIRE_WORKDIR_SETUP", raising=False)
    reset_settings()
    await init_db(str(db_path))
    db = await aiosqlite.connect(str(db_path))
    await db.execute("PRAGMA foreign_keys=ON;")
    db.row_factory = aiosqlite.Row
    auth = AuthService(db)
    await auth.create_user("root", "password123", is_admin=True)
    await auth.create_user("alice", "password123", is_admin=False)

    from app.main import app

    transport = ASGITransport(app=app)
    try:
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            tokens = {}
            for name in ("root", "alice"):
                response = await client.post(
                    "/api/v1/auth/login", json={"username": name, "password": "password123"}
                )
                tokens[name] = {"Authorization": f"Bearer {response.json()['access_token']}"}
            yield db, client, tokens, str(storage_root)
    finally:
        await db.close()
        for fd in vs._presence_fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        vs._presence_fds.clear()
        reset_settings()


async def _user_id(db, name: str) -> int:
    cursor = await db.execute("SELECT id FROM users WHERE username = ?", (name,))
    return (await cursor.fetchone())["id"]


async def _record(db, user_id: int, path: str, *, purged: bool = False) -> int:
    cursor = await db.execute(
        """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
           device_name, file_size, file_name, deleted_at, purged_at)
           VALUES (?, ?, ?, 'content://1', 'Pixel9Pro', 10, ?, ?, ?)""",
        (
            user_id, f"hash-{os.path.basename(path)}", path, os.path.basename(path),
            "2026-09-01T00:00:00+00:00" if purged else None,
            "2026-10-01T00:00:00+00:00" if purged else None,
        ),
    )
    await db.commit()
    return cursor.lastrowid


async def _version(db, record_id: int, user_id: int, stored: Path, *, size: int, expires: str):
    stored.parent.mkdir(parents=True, exist_ok=True)
    stored.write_bytes(b"v" * size)
    await db.execute(
        """INSERT INTO file_versions (file_record_id, user_id, file_hash, file_size,
           stored_path, replaced_at, expires_at)
           VALUES (?, ?, ?, ?, ?, '2026-09-01T00:00:00+00:00', ?)""",
        (record_id, user_id, f"old-{stored.name}", size, str(stored), expires),
    )
    await db.commit()


def _versions_dir(storage_root: str, record_id: int) -> Path:
    return Path(os.path.realpath(storage_root)) / ".versions" / str(record_id)


class TestClearPurgedRecords:
    async def test_records_with_versions_are_cleared(self, env):
        db, client, tokens, storage_root = env
        alice = await _user_id(db, "alice")
        purged = await _record(db, alice, f"{storage_root}/alice/.trash/a.jpg", purged=True)
        active = await _record(db, alice, f"{storage_root}/alice/Pixel9Pro/b.jpg")
        gone = _versions_dir(storage_root, purged) / "20260901T000000Z_aaaaaaaa.jpg"
        kept = _versions_dir(storage_root, active) / "20260901T000000Z_bbbbbbbb.jpg"
        await _version(db, purged, alice, gone, size=4, expires="2099-01-01T00:00:00+00:00")
        await _version(db, active, alice, kept, size=4, expires="2099-01-01T00:00:00+00:00")

        response = await client.delete(
            f"/api/v1/admin/users/{alice}/purged-records", headers=tokens["root"]
        )

        assert response.status_code == 200
        assert response.json() == {"success": True, "count": 1}
        cursor = await db.execute("SELECT file_record_id FROM file_versions")
        assert [r["file_record_id"] for r in await cursor.fetchall()] == [active]
        cursor = await db.execute("SELECT id FROM file_records")
        assert [r["id"] for r in await cursor.fetchall()] == [active]
        assert not gone.exists() and not gone.parent.exists()
        assert kept.exists()


class TestServerAboutVersionStorage:
    async def test_admin_sees_every_existing_version(self, env):
        """Acceptance 22: expired 100 + live 250 -> count 2, bytes 350."""
        db, client, tokens, storage_root = env
        alice = await _user_id(db, "alice")
        record = await _record(db, alice, f"{storage_root}/alice/Pixel9Pro/c.jpg")
        directory = _versions_dir(storage_root, record)
        await _version(db, record, alice, directory / "20260101T000000Z_aaaaaaaa.jpg",
                       size=100, expires="2026-01-31T00:00:00+00:00")
        await _version(db, record, alice, directory / "20260901T000000Z_bbbbbbbb.jpg",
                       size=250, expires="2099-01-01T00:00:00+00:00")

        response = await client.get("/api/v1/server/about", headers=tokens["root"])

        assert response.status_code == 200
        storage = response.json()["storage"]
        assert (storage["versions_count"], storage["versions_bytes"]) == (2, 350)

    async def test_admin_without_versions_sees_zero(self, env):
        db, client, tokens, storage_root = env
        response = await client.get("/api/v1/server/about", headers=tokens["root"])
        storage = response.json()["storage"]
        assert (storage["versions_count"], storage["versions_bytes"]) == (0, 0)

    async def test_query_failure_reports_none(self, env, monkeypatch):
        db, client, tokens, storage_root = env

        async def broken(url):
            raise aiosqlite.OperationalError("unable to open database file")

        monkeypatch.setattr("app.api.server._create_connection", broken)
        response = await client.get("/api/v1/server/about", headers=tokens["root"])
        assert response.status_code == 200
        storage = response.json()["storage"]
        assert storage["versions_count"] is None and storage["versions_bytes"] is None

    async def test_non_admin_gets_no_storage(self, env):
        db, client, tokens, storage_root = env
        response = await client.get("/api/v1/server/about", headers=tokens["alice"])
        assert response.status_code == 200
        assert response.json()["storage"] is None
