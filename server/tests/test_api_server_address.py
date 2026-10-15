"""Integration tests for the server-address endpoints.

Tests:
- GET /api/v1/server/connect-qr
- GET /api/v1/server/address-settings
- PUT /api/v1/server/address-settings
- GET /api/v1/server/info (key set unchanged)

服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json.
"""

from __future__ import annotations

import json
import logging

import aiosqlite
import pytest
from httpx import ASGITransport, AsyncClient

from app.core.config import reset_settings, server_address_settings_path
from app.core.database import init_db
from app.services.auth_service import AuthService

CONNECT_QR_KEYS = {"version", "payload", "public_address", "public_source", "lan_address", "lan_source"}
EMPTY_SETTINGS = {"public_address": "", "lan_address_override": ""}


@pytest.fixture(autouse=True)
def _reset_settings():
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
def storage_root(tmp_path):
    return tmp_path / "storage"


@pytest.fixture
async def db(tmp_path, storage_root, monkeypatch):
    """Temp database with schema; env managed by monkeypatch."""
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(storage_root))
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", str(tmp_path / "test.db"))
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-server-address-tests")
    monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")
    # test_api_admin leaks PHOTOVAULT_MAX_USERS=1 into os.environ.
    monkeypatch.delenv("PHOTOVAULT_MAX_USERS", raising=False)
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
    reset_settings()


@pytest.fixture
async def admin_db(db):
    await AuthService(db).create_user("admin", "adminpass123", is_admin=True)
    return db


@pytest.fixture
async def admin_token(admin_db):
    return (await AuthService(admin_db).login("admin", "adminpass123")).access_token


@pytest.fixture
async def regular_user_token(admin_db):
    auth_service = AuthService(admin_db)
    await auth_service.create_user("regular", "regularpass123", is_admin=False)
    return (await auth_service.login("regular", "regularpass123")).access_token


@pytest.fixture
def async_client():
    from app.main import app

    return AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver")


@pytest.fixture
def detected(monkeypatch):
    """Set what detect_lan_ips() returns; defaults to a single LAN IP."""
    ips: list[str] = ["192.168.1.10"]
    monkeypatch.setattr("app.api.server.detect_lan_ips", lambda: list(ips))
    return ips


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _settings_file(storage_root):
    return server_address_settings_path(str(storage_root))


async def _put(client, token, public, lan):
    return await client.put(
        "/api/v1/server/address-settings",
        headers=_auth(token),
        json={"public_address": public, "lan_address_override": lan},
    )


# ---------------------------------------------------------------------------
# Auth (AC-1, AC-2)
# ---------------------------------------------------------------------------


class TestAuth:
    async def test_connect_qr_requires_login(self, admin_db, async_client, detected):
        async with async_client as client:
            response = await client.get("/api/v1/server/connect-qr")
        assert response.status_code == 401

    async def test_connect_qr_allowed_for_regular_user(self, regular_user_token, async_client, detected):
        async with async_client as client:
            response = await client.get("/api/v1/server/connect-qr", headers=_auth(regular_user_token))
        assert response.status_code == 200

    async def test_address_settings_forbidden_for_regular_user(
        self, regular_user_token, async_client, detected, storage_root
    ):
        async with async_client as client:
            get_resp = await client.get("/api/v1/server/address-settings", headers=_auth(regular_user_token))
            put_resp = await _put(client, regular_user_token, "https://photos.example.com", None)
        assert get_resp.status_code == 403
        assert put_resp.status_code == 403
        assert not _settings_file(storage_root).exists()

    async def test_address_settings_require_login(self, admin_db, async_client, detected):
        async with async_client as client:
            get_resp = await client.get("/api/v1/server/address-settings")
            put_resp = await client.put(
                "/api/v1/server/address-settings",
                json={"public_address": None, "lan_address_override": None},
            )
        assert get_resp.status_code == 401
        assert put_resp.status_code == 401


# ---------------------------------------------------------------------------
# address-settings read / write (AC-2, AC-3)
# ---------------------------------------------------------------------------


class TestAddressSettings:
    async def test_initial_get(self, admin_token, async_client, detected):
        async with async_client as client:
            response = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))
        assert response.status_code == 200
        assert response.json() == {**EMPTY_SETTINGS, "detected_lan_address": "http://192.168.1.10:8000"}

    async def test_detected_is_null_when_nothing_found(self, admin_token, async_client, detected):
        detected.clear()
        async with async_client as client:
            response = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))
        assert response.json()["detected_lan_address"] is None

    async def test_admin_saves_canonical_value_and_logs(
        self, admin_token, async_client, detected, monkeypatch, caplog
    ):
        monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
        with caplog.at_level(logging.INFO, logger="photovault.api.server"):
            async with async_client as client:
                put_resp = await _put(client, admin_token, "HTTPS://Photos.Example.com:443/", None)
                get_resp = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))

        expected = {
            "public_address": "https://photos.example.com",
            "lan_address_override": "",
            "detected_lan_address": "http://192.168.1.10:8000",
        }
        assert put_resp.status_code == 200
        assert put_resp.json() == expected
        assert get_resp.json() == expected

        infos = [
            r.getMessage() for r in caplog.records
            if r.name == "photovault.api.server" and r.levelno == logging.INFO
        ]
        assert infos == [
            "Server addresses updated by admin: "
            "public_address=https://photos.example.com lan_address_override=(none)"
        ]

    async def test_values_survive_settings_reset(self, admin_token, async_client, detected, storage_root):
        # AC-3
        async with async_client as client:
            put_resp = await _put(client, admin_token, "https://photos.example.com", "http://nas.local:8000")
            assert put_resp.status_code == 200
            reset_settings()
            get_resp = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))

        assert get_resp.json()["public_address"] == "https://photos.example.com"
        assert get_resp.json()["lan_address_override"] == "http://nas.local:8000"
        assert json.loads(_settings_file(storage_root).read_text("utf-8")) == {
            "public_address": "https://photos.example.com",
            "lan_address_override": "http://nas.local:8000",
        }

    async def test_null_and_blank_clear_values(self, admin_token, async_client, detected):
        async with async_client as client:
            await _put(client, admin_token, "https://photos.example.com", "http://nas.local:8000")
            response = await _put(client, admin_token, None, "   ")
        assert response.status_code == 200
        assert response.json()["public_address"] == ""
        assert response.json()["lan_address_override"] == ""


# ---------------------------------------------------------------------------
# Validation (AC-4)
# ---------------------------------------------------------------------------

INVALID_VALUES = [
    ("ftp://a", "BAD_SCHEME", "仅支持 http:// 或 https://"),
    ("http://a:70000", "BAD_PORT", "端口须在 1–65535 之间"),
    ("http://a/path", "HAS_EXTRA", "地址只能包含协议、主机和端口"),
    ("http://u:p@a", "HAS_EXTRA", "地址只能包含协议、主机和端口"),
    ("http://a b", "INVALID", "地址格式不正确"),
    ("http://例子.com", "INVALID", "地址格式不正确"),
    ("http://" + "a" * 250, "TOO_LONG", "地址过长（最多 255 个字符）"),
]


class TestValidation:
    @pytest.mark.parametrize("field", ["public_address", "lan_address_override"])
    @pytest.mark.parametrize("value, code, message", INVALID_VALUES, ids=[v[1] + ":" + v[0][:20] for v in INVALID_VALUES])
    async def test_invalid_value_is_rejected(
        self, admin_token, async_client, detected, field, value, code, message
    ):
        body = {"public_address": "https://photos.example.com", "lan_address_override": None}
        body[field] = value
        async with async_client as client:
            response = await client.put(
                "/api/v1/server/address-settings", headers=_auth(admin_token), json=body
            )
            after = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))

        assert response.status_code == 422
        assert response.json()["detail"] == {
            "code": "invalid_address",
            "message": message,
            "fields": {field: {"code": code, "message": message}},
        }
        assert after.json()["public_address"] == ""
        assert after.json()["lan_address_override"] == ""

    async def test_rejection_keeps_previous_values(self, admin_token, async_client, detected):
        async with async_client as client:
            await _put(client, admin_token, "https://photos.example.com", "http://nas.local:8000")
            response = await _put(client, admin_token, "ftp://a", "http://a/path")
            after = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))

        detail = response.json()["detail"]
        assert response.status_code == 422
        # message comes from the first failing field (public before lan).
        assert detail["message"] == "仅支持 http:// 或 https://"
        assert set(detail["fields"]) == {"public_address", "lan_address_override"}
        assert after.json()["public_address"] == "https://photos.example.com"
        assert after.json()["lan_address_override"] == "http://nas.local:8000"

    async def test_identical_addresses_flag_both_fields(self, admin_token, async_client, detected):
        async with async_client as client:
            response = await _put(client, admin_token, "http://NAS.local", "nas.local:80")

        assert response.status_code == 422
        assert response.json()["detail"] == {
            "code": "invalid_address",
            "message": "与局域网地址相同",
            "fields": {
                "public_address": {"code": "SAME_AS_OTHER", "message": "与局域网地址相同"},
                "lan_address_override": {"code": "SAME_AS_OTHER", "message": "与公网地址相同"},
            },
        }

    async def test_missing_key_is_standard_validation_error(self, admin_token, async_client, detected):
        async with async_client as client:
            response = await client.put(
                "/api/v1/server/address-settings",
                headers=_auth(admin_token),
                json={"public_address": "https://photos.example.com"},
            )
        assert response.status_code == 422
        assert isinstance(response.json()["detail"], list)


# ---------------------------------------------------------------------------
# connect-qr (AC-6, AC-7, AC-9)
# ---------------------------------------------------------------------------


class TestConnectQr:
    async def _get(self, client, token, origin=None):
        params = {} if origin is None else {"origin": origin}
        response = await client.get("/api/v1/server/connect-qr", headers=_auth(token), params=params)
        assert response.status_code == 200
        return response.json()

    async def test_configured_both(self, admin_token, async_client, detected):
        async with async_client as client:
            await _put(client, admin_token, "https://photos.example.com", "http://192.168.1.20:8000")
            body = await self._get(client, admin_token, origin="http://localhost:5173")

        assert set(body) == CONNECT_QR_KEYS
        assert body == {
            "version": 1,
            "payload": "photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com"
            "&lan=http%3A%2F%2F192.168.1.20%3A8000",
            "public_address": "https://photos.example.com",
            "public_source": "configured",
            "lan_address": "http://192.168.1.20:8000",
            "lan_source": "configured",
        }

    @pytest.mark.parametrize(
        "origin, public, public_source, lan, lan_source",
        [
            (None, None, None, "http://192.168.1.10:8000", "detected"),
            ("https://photos.example.com", "https://photos.example.com", "origin", "http://192.168.1.10:8000", "detected"),
            ("HTTPS://Photos.Example.com", "https://photos.example.com", "origin", "http://192.168.1.10:8000", "detected"),
            ("http://localhost:5173", None, None, "http://192.168.1.10:8000", "detected"),
            ("http://nas.local:8000", None, None, "http://nas.local:8000", "origin"),
            ("null", None, None, "http://192.168.1.10:8000", "detected"),
        ],
    )
    async def test_origin_fallback(
        self, regular_user_token, async_client, detected, origin, public, public_source, lan, lan_source
    ):
        # AC-6 over HTTP.
        async with async_client as client:
            body = await self._get(client, regular_user_token, origin=origin)
        assert (body["public_address"], body["public_source"]) == (public, public_source)
        assert (body["lan_address"], body["lan_source"]) == (lan, lan_source)

    async def test_overlong_origin_is_ignored(self, regular_user_token, async_client, detected):
        async with async_client as client:
            body = await self._get(client, regular_user_token, origin="https://" + "a" * 10001 + ".com")
        assert body["public_address"] is None
        assert body["lan_source"] == "detected"

    async def test_identical_public_and_detected_lan_are_deduplicated(
        self, admin_token, async_client, detected
    ):
        # AC-7
        async with async_client as client:
            await _put(client, admin_token, "http://192.168.1.10:8000", None)
            body = await self._get(client, admin_token)
        assert body["public_address"] == "http://192.168.1.10:8000"
        assert body["lan_address"] is None
        assert body["lan_source"] is None
        assert body["payload"] == "photovault://connect?v=1&public=http%3A%2F%2F192.168.1.10%3A8000"

    async def test_payload_null_without_any_address(self, regular_user_token, async_client, detected):
        detected.clear()
        async with async_client as client:
            body = await self._get(client, regular_user_token, origin="http://localhost:5173")
        assert body == {
            "version": 1,
            "payload": None,
            "public_address": None,
            "public_source": None,
            "lan_address": None,
            "lan_source": None,
        }

    async def test_server_info_keys_unchanged(self, regular_user_token, async_client, detected):
        # AC-9
        async with async_client as client:
            info = await client.get("/api/v1/server/info", headers=_auth(regular_user_token))
            qr = await client.get("/api/v1/server/connect-qr", headers=_auth(regular_user_token))
        assert set(info.json()) == {"lan_ips", "port"}
        assert set(qr.json()) == CONNECT_QR_KEYS


# ---------------------------------------------------------------------------
# Failure handling
# ---------------------------------------------------------------------------


class TestFailures:
    async def test_write_failure_returns_500_and_keeps_file(
        self, admin_token, async_client, detected, storage_root, monkeypatch
    ):
        async with async_client as client:
            await _put(client, admin_token, "https://a.example.com", None)
            before = _settings_file(storage_root).read_bytes()

            def boom(*args, **kwargs):
                raise OSError("disk full")

            monkeypatch.setattr("app.core.config.os.replace", boom)
            response = await _put(client, admin_token, "https://b.example.com", None)

        assert response.status_code == 500
        assert response.json() == {"detail": "保存服务器地址失败"}
        assert _settings_file(storage_root).read_bytes() == before
        assert list(storage_root.glob(".server_address.*.tmp")) == []

    async def test_broken_file_can_be_repaired(
        self, admin_token, async_client, detected, storage_root
    ):
        path = _settings_file(storage_root)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'{"public_address": "\xb9\xab"}')  # GBK, not UTF-8

        async with async_client as client:
            settings_resp = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))
            qr_resp = await client.get("/api/v1/server/connect-qr", headers=_auth(admin_token))
            put_resp = await _put(client, admin_token, "https://photos.example.com", None)
            after = await client.get("/api/v1/server/address-settings", headers=_auth(admin_token))

        assert settings_resp.status_code == 200
        assert settings_resp.json()["public_address"] == ""
        assert settings_resp.json()["lan_address_override"] == ""
        assert qr_resp.status_code == 200
        assert qr_resp.json()["public_address"] is None
        assert put_resp.status_code == 200
        assert after.json()["public_address"] == "https://photos.example.com"
