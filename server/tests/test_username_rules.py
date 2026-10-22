"""Username rule for NEW accounts (audit finding F5).

Covers ``app.core.validators.validate_username`` and the case-insensitive
uniqueness check in ``AuthService.create_user``.
"""

import aiosqlite
import pytest

from app.core.config import reset_settings
from app.core.database import init_db
from app.core.validators import (
    BOOTSTRAP_ADMIN_USERNAME,
    RESERVED_USERNAMES,
    USERNAME_RESERVED_MESSAGE,
    USERNAME_RULE_MESSAGE,
    validate_username,
)
from app.services.auth_service import AuthService

EXPECTED_RESERVED = {
    # product decision
    "admin", "root", "system", "api", "static",
    # API route prefixes
    "setup", "auth", "connection", "backup", "files", "server", "explore",
    "stats", "clients", "fnos", "health",
    # static mount
    "assets",
    # Web SPA routes
    "photos", "timeline", "statistics", "trash", "settings", "devices",
    "login", "register", "fnos-auth-callback", "app-auth",
    # storage-root siblings
    "logs", "client-releases",
    # app name
    "photovault",
    # Windows device names
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
}


# ---------------------------------------------------------------------------
# validate_username
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name",
    [
        "abc", "huo", "qiao", "vercheck", "123", "a1b", "user_01", "user-01",
        "a-b_c", "a" * 32, "com10", "console", "administrator",
    ],
)
def test_valid_usernames(name):
    assert validate_username(name) is None


@pytest.mark.parametrize(
    "name",
    [
        "", "a", "ab", "a" * 33,
        "Alice", "ALICE", "aLice",
        ".", "..", ".thumbnails", ".chunks", ".versions", ".trash", ".locks", "a.b",
        "-abc", "abc-", "_abc", "abc_",
        "a b", " abc", "abc ", "abc\n", "ab\x00c", "a/b", "a\\b",
        "霍", "张三", "alicé", "ａｂｃ",
    ],
)
def test_rule_violations_get_rule_message(name):
    assert validate_username(name) == USERNAME_RULE_MESSAGE


def test_reserved_set_is_exact():
    assert RESERVED_USERNAMES == EXPECTED_RESERVED


@pytest.mark.parametrize("name", sorted(EXPECTED_RESERVED))
def test_every_reserved_name_is_rejected(name):
    assert validate_username(name) == USERNAME_RESERVED_MESSAGE


@pytest.mark.parametrize(
    "name",
    ["ADMIN", "Admin", "aDmIn", "CON", "Con", "Com1", "COM9", "lPt9", "LPT1", "Nul", "ROOT"],
)
def test_reserved_names_in_other_cases_are_rejected(name):
    # Uppercase already fails the regex; the point is that none slip through.
    assert validate_username(name) is not None


@pytest.mark.parametrize("name", sorted(EXPECTED_RESERVED))
def test_reserved_names_upper_and_title_case_rejected(name):
    assert validate_username(name.upper()) is not None
    assert validate_username(name.title()) is not None


def test_bootstrap_admin_exemption_is_only_admin():
    assert BOOTSTRAP_ADMIN_USERNAME == "admin"
    assert validate_username("admin", bootstrap_admin=True) is None
    assert validate_username("admin") == USERNAME_RESERVED_MESSAGE
    assert validate_username("root", bootstrap_admin=True) == USERNAME_RESERVED_MESSAGE
    assert validate_username("con", bootstrap_admin=True) == USERNAME_RESERVED_MESSAGE
    assert validate_username("Admin", bootstrap_admin=True) == USERNAME_RULE_MESSAGE
    assert validate_username("myadmin", bootstrap_admin=True) is None


# ---------------------------------------------------------------------------
# Case-insensitive uniqueness in AuthService.create_user
# ---------------------------------------------------------------------------


@pytest.fixture
async def db(tmp_path, monkeypatch):
    db_path = str(tmp_path / "test.db")
    monkeypatch.setenv("PHOTOVAULT_MAX_USERS", "20")
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path / "storage"))
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", db_path)
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-username-rules")
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


async def _user_count(conn) -> int:
    cursor = await conn.execute("SELECT COUNT(*) FROM users")
    return (await cursor.fetchone())[0]


@pytest.mark.parametrize("legacy", ["Alice", "ALICE"])
async def test_new_name_equal_to_legacy_name_ignoring_case_is_rejected(db, legacy):
    await db.execute(
        "INSERT INTO users (username, password_hash) VALUES (?, 'x')", (legacy,)
    )
    await db.commit()
    with pytest.raises(ValueError, match="already exists"):
        await AuthService(db).create_user("alice", "alicepass123")
    assert await _user_count(db) == 1
    assert not db.in_transaction


async def test_distinct_name_is_still_created(db):
    await db.execute("INSERT INTO users (username, password_hash) VALUES ('Alice', 'x')")
    await db.commit()
    user = await AuthService(db).create_user("bob", "bobpass123")
    assert user.username == "bob"
    assert await _user_count(db) == 2
