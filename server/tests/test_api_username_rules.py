"""API tests for the username rule on every user-creating entry point (F5).

- POST /api/v1/auth/register
- POST /api/v1/admin/users
- POST /api/v1/setup/init (bootstrap: only "admin" is exempt from the reserved list)

Existing (legacy) users whose names break the new rule keep logging in with
an exact match and keep using the API.
"""

import aiosqlite
import pytest
from httpx import ASGITransport, AsyncClient

from app.core.config import reset_settings
from app.core.database import init_db
from app.core.validators import USERNAME_RESERVED_MESSAGE, USERNAME_RULE_MESSAGE
from app.services.auth_service import _hash_password

ADMIN_PASSWORD = "adminpass123"
LEGACY_PASSWORD = "legacypass1"


@pytest.fixture
async def empty_db(tmp_path, monkeypatch):
    """Schema only, no users. max_users is pinned so leaked env cannot interfere."""
    db_path = str(tmp_path / "test.db")
    monkeypatch.setenv("PHOTOVAULT_MAX_USERS", "20")
    monkeypatch.setenv("PHOTOVAULT_ALLOW_REGISTRATION", "true")
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path / "storage"))
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", db_path)
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-username-api")
    reset_settings()
    await init_db(db_path)
    conn = await aiosqlite.connect(db_path)
    conn.row_factory = aiosqlite.Row
    await conn.execute("PRAGMA foreign_keys=ON;")
    try:
        yield conn
    finally:
        await conn.close()
        reset_settings()


@pytest.fixture
async def db(empty_db):
    """Initialized system: an admin named "admin" (inserted directly)."""
    await empty_db.execute(
        "INSERT INTO users (id, username, password_hash, is_admin) VALUES (1, 'admin', ?, 1)",
        (_hash_password(ADMIN_PASSWORD),),
    )
    await empty_db.commit()
    return empty_db


@pytest.fixture
async def client():
    from app.main import app

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as c:
        yield c


async def _admin_headers(client) -> dict:
    resp = await client.post(
        "/api/v1/auth/login", json={"username": "admin", "password": ADMIN_PASSWORD}
    )
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


async def _insert_legacy(conn, username: str) -> None:
    await conn.execute(
        "INSERT INTO users (username, password_hash) VALUES (?, ?)",
        (username, _hash_password(LEGACY_PASSWORD)),
    )
    await conn.commit()


async def _usernames(conn) -> list[str]:
    cursor = await conn.execute("SELECT username FROM users ORDER BY id")
    return [row[0] for row in await cursor.fetchall()]


# ---------------------------------------------------------------------------
# POST /auth/register
# ---------------------------------------------------------------------------


async def test_register_valid_name(db, client):
    resp = await client.post(
        "/api/v1/auth/register", json={"username": "alice2", "password": "alicepass1"}
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["access_token"]
    assert "alice2" in await _usernames(db)


@pytest.mark.parametrize("name", ["Alice", ".thumbnails", "霍", " alice", "alice "])
async def test_register_rejects_rule_violations(db, client, name):
    resp = await client.post(
        "/api/v1/auth/register", json={"username": name, "password": "alicepass1"}
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == USERNAME_RULE_MESSAGE
    assert await _usernames(db) == ["admin"]


@pytest.mark.parametrize("name", ["root", "con", "admin", "logs"])
async def test_register_rejects_reserved_names(db, client, name):
    resp = await client.post(
        "/api/v1/auth/register", json={"username": name, "password": "alicepass1"}
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == USERNAME_RESERVED_MESSAGE


async def test_register_disabled_still_403(db, client, monkeypatch):
    monkeypatch.setenv("PHOTOVAULT_ALLOW_REGISTRATION", "false")
    reset_settings()
    resp = await client.post(
        "/api/v1/auth/register", json={"username": "Bad.Name", "password": "alicepass1"}
    )
    assert resp.status_code == 403


async def test_register_blank_name_keeps_empty_message(db, client):
    resp = await client.post(
        "/api/v1/auth/register", json={"username": "   ", "password": "alicepass1"}
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == "用户名不能为空"


async def test_register_case_insensitive_duplicate_of_legacy_user(db, client):
    await _insert_legacy(db, "Alice")
    resp = await client.post(
        "/api/v1/auth/register", json={"username": "alice", "password": "alicepass1"}
    )
    assert resp.status_code == 400
    assert "already exists" in resp.json()["detail"]


# ---------------------------------------------------------------------------
# POST /admin/users
# ---------------------------------------------------------------------------


async def test_admin_create_valid_name(db, client):
    headers = await _admin_headers(client)
    resp = await client.post(
        "/api/v1/admin/users",
        headers=headers,
        json={"username": "newuser1", "password": "newuserpass1"},
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["username"] == "newuser1"


@pytest.mark.parametrize(
    ("name", "is_admin", "message"),
    [
        ("Bad.Name", False, USERNAME_RULE_MESSAGE),
        ("api", False, USERNAME_RESERVED_MESSAGE),
        # No bootstrap exemption outside /setup/init, also for admin accounts.
        ("admin", True, USERNAME_RESERVED_MESSAGE),
    ],
)
async def test_admin_create_rejects_invalid_names(db, client, name, is_admin, message):
    headers = await _admin_headers(client)
    resp = await client.post(
        "/api/v1/admin/users",
        headers=headers,
        json={"username": name, "password": "newuserpass1", "is_admin": is_admin},
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == message
    assert await _usernames(db) == ["admin"]


async def test_admin_create_case_insensitive_duplicate_of_legacy_user(db, client):
    await _insert_legacy(db, "Alice")
    headers = await _admin_headers(client)
    resp = await client.post(
        "/api/v1/admin/users",
        headers=headers,
        json={"username": "alice", "password": "alicepass1"},
    )
    assert resp.status_code == 400
    assert "already exists" in resp.json()["detail"]


# ---------------------------------------------------------------------------
# POST /setup/init
# ---------------------------------------------------------------------------


async def test_setup_allows_bootstrap_admin(empty_db, client):
    resp = await client.post(
        "/api/v1/setup/init", json={"username": "admin", "password": "securepass123"}
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["username"] == "admin"


@pytest.mark.parametrize(
    ("name", "message"),
    [
        ("root", USERNAME_RESERVED_MESSAGE),
        ("con", USERNAME_RESERVED_MESSAGE),
        ("Admin", USERNAME_RULE_MESSAGE),
        (" admin", USERNAME_RULE_MESSAGE),
        ("霍", USERNAME_RULE_MESSAGE),
    ],
)
async def test_setup_rejects_invalid_names(empty_db, client, name, message):
    resp = await client.post(
        "/api/v1/setup/init", json={"username": name, "password": "securepass123"}
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == message
    assert await _usernames(empty_db) == []


# ---------------------------------------------------------------------------
# Legacy users are unaffected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("legacy", ["Legacy.User", "霍", ".thumbnails"])
async def test_legacy_user_with_now_invalid_name_still_works(db, client, legacy):
    await _insert_legacy(db, legacy)
    login = await client.post(
        "/api/v1/auth/login", json={"username": legacy, "password": LEGACY_PASSWORD}
    )
    assert login.status_code == 200, login.text
    tokens = login.json()

    listed = await client.get(
        "/api/v1/admin/users", headers={"Authorization": f"Bearer {tokens['access_token']}"}
    )
    assert listed.status_code == 200
    assert [u["username"] for u in listed.json()] == [legacy]

    refreshed = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["access_token"]


async def test_legacy_login_stays_exact_match(db, client):
    await _insert_legacy(db, "Legacy.User")
    resp = await client.post(
        "/api/v1/auth/login", json={"username": "legacy.user", "password": LEGACY_PASSWORD}
    )
    assert resp.status_code == 401
