"""Tests for app.core.server_address and the .server_address.json persistence.

服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json.

The shared vectors are read from the repo-level ``protocol/server-qr-v1.json``
(the Android JVM tests read the same file). The server runs five groups:
address_validation, server_web_only, address_pairs, host_classification and
payloads. ``decode`` belongs to the Android parser only.
"""

from __future__ import annotations

import json
import logging
import stat
from pathlib import Path
from urllib.parse import unquote

import pytest

from app.core.config import (
    get_server_address_settings,
    reset_settings,
    server_address_settings_path,
    set_server_address_settings,
)
from app.core.server_address import (
    ADDRESS_ERROR_MESSAGES,
    MAX_ADDRESS_LENGTH,
    MAX_PAYLOAD_LENGTH,
    PAYLOAD_PREFIX,
    AddressCheck,
    AddressError,
    EffectiveAddresses,
    detected_lan_address,
    encode_connect_payload,
    host_scope,
    resolve_effective_addresses,
    same_as_other_message,
    validate_address,
    validate_address_pair,
)

# A missing file must fail the run, not skip it.
VECTORS = json.loads(
    (Path(__file__).resolve().parents[2] / "protocol" / "server-qr-v1.json").read_text("utf-8")
)


def _err(code):
    return AddressError(code) if code is not None else None


def _decode_v1(payload: str) -> dict[str, str | None]:
    """Minimal v1 decoder for round-trip checks (the real parser is Android's)."""
    assert payload.startswith(PAYLOAD_PREFIX)
    params: dict[str, str] = {}
    for part in payload[len(PAYLOAD_PREFIX):].split("&"):
        key, _, value = part.partition("=")
        assert key not in params
        params[key] = value
    assert params.pop("v") == "1"
    assert set(params) <= {"public", "lan"}
    return {k: (unquote(params[k]) if k in params else None) for k in ("public", "lan")}


# ---------------------------------------------------------------------------
# Shared vectors
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("vec", VECTORS["address_validation"], ids=lambda v: repr(v["input"])[:60])
def test_address_validation_vectors(vec):
    assert validate_address(vec["input"]) == AddressCheck(
        vec.get("canonical"), _err(vec.get("error"))
    )


@pytest.mark.parametrize("vec", VECTORS["server_web_only"], ids=lambda v: repr(v["input"])[:60])
def test_server_web_only_vectors(vec):
    assert validate_address(vec["input"]) == AddressCheck(None, AddressError(vec["error"]))


@pytest.mark.parametrize("vec", VECTORS["address_pairs"], ids=lambda v: f"{v['public']!r}|{v['lan']!r}")
def test_address_pairs_vectors(vec):
    pub, lan = validate_address_pair(vec["public"], vec["lan"])
    assert pub == AddressCheck(vec["public_canonical"], _err(vec["public_error"]))
    assert lan == AddressCheck(vec["lan_canonical"], _err(vec["lan_error"]))


@pytest.mark.parametrize("vec", VECTORS["host_classification"], ids=lambda v: v["address"])
def test_host_classification_vectors(vec):
    check = validate_address(vec["address"])
    assert check == AddressCheck(vec["canonical"], None)
    assert host_scope(check.canonical) == vec["scope"]


@pytest.mark.parametrize("vec", VECTORS["payloads"], ids=lambda v: v["payload"][22:70])
def test_payload_vectors(vec):
    payload = encode_connect_payload(vec["public"], vec["lan"])
    assert payload == vec["payload"]
    assert _decode_v1(payload) == {"public": vec["public"], "lan": vec["lan"]}


# ---------------------------------------------------------------------------
# Validation extras (design §7.3)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("raw", ["http://a\t/", "http://a\r/", "http://a:80\n/"])
def test_control_characters_in_hostport_are_invalid(raw):
    assert validate_address(raw) == AddressCheck(None, AddressError.INVALID)


def test_trailing_newline_port_is_not_accepted_by_int():
    # int("80\n") == 80; the full-match rule must stop it.
    assert validate_address("http://a:80\n/").canonical is None


def test_none_input_clears():
    assert validate_address(None) == AddressCheck(None, None)


def test_error_messages_cover_five_codes():
    assert set(ADDRESS_ERROR_MESSAGES) == set(AddressError) - {AddressError.SAME_AS_OTHER}
    assert ADDRESS_ERROR_MESSAGES[AddressError.BAD_SCHEME] == "仅支持 http:// 或 https://"


def test_same_as_other_message():
    assert same_as_other_message("public_address") == "与局域网地址相同"
    assert same_as_other_message("lan_address_override") == "与公网地址相同"
    with pytest.raises(ValueError):
        same_as_other_message("public")  # type: ignore[arg-type]


def _host_of_length(n: int, first: str) -> str:
    """A valid host name of exactly ``n`` characters (labels of <= 63)."""
    labels = []
    remaining = n
    ch = first
    while remaining > 0:
        size = min(63, remaining)
        labels.append(ch * size)
        remaining -= size + 1  # +1 for the dot before the next label
        ch = chr(ord(ch) + 1)
    host = ".".join(labels)
    assert len(host) == n
    return host


def test_two_max_length_addresses_fit_payload_limit():
    # ":65535" adds the extra %3A growth; both addresses are exactly 255 chars.
    public = "https://" + _host_of_length(255 - 8 - 6, "a") + ":65535"
    lan = "http://" + _host_of_length(255 - 7 - 6, "k") + ":65534"
    assert len(public) == len(lan) == MAX_ADDRESS_LENGTH
    assert validate_address(public) == AddressCheck(public, None)
    assert validate_address(lan) == AddressCheck(lan, None)

    payload = encode_connect_payload(public, lan)
    assert len(payload) == MAX_PAYLOAD_LENGTH
    assert _decode_v1(payload) == {"public": public, "lan": lan}


# ---------------------------------------------------------------------------
# encode_connect_payload contract
# ---------------------------------------------------------------------------


def test_encode_rejects_non_canonical():
    with pytest.raises(ValueError):
        encode_connect_payload("HTTPS://A.com", None)


def test_encode_rejects_identical_addresses():
    with pytest.raises(ValueError):
        encode_connect_payload("http://a", "http://a")


def test_encode_returns_none_without_addresses():
    assert encode_connect_payload(None, "") is None
    assert encode_connect_payload(None, None) is None


# ---------------------------------------------------------------------------
# detected_lan_address / resolve_effective_addresses
# ---------------------------------------------------------------------------


def test_detected_lan_address():
    assert detected_lan_address([], 8000) is None
    assert detected_lan_address(["192.168.1.10"], 80) == "http://192.168.1.10"
    assert detected_lan_address(["192.168.1.10", "10.0.0.5"], 8000) == "http://192.168.1.10:8000"


def _resolve(**overrides) -> EffectiveAddresses:
    kwargs = dict(
        configured_public="",
        configured_lan="",
        origin=None,
        detected_lan_ips=[],
        server_port=8000,
    )
    kwargs.update(overrides)
    return resolve_effective_addresses(**kwargs)


def test_only_first_detected_ip_is_used():
    eff = _resolve(detected_lan_ips=["a..b", "192.168.1.10"])
    assert eff.lan_address is None and eff.lan_source is None


def test_configured_and_detected_without_origin():
    # AC-5
    eff = _resolve(configured_public="https://photos.example.com", detected_lan_ips=["192.168.1.10"])
    assert eff == EffectiveAddresses(
        "https://photos.example.com", "configured", "http://192.168.1.10:8000", "detected"
    )


def test_configured_lan_wins_over_origin_and_detection():
    eff = _resolve(
        configured_lan="http://nas.local:8000",
        origin="http://192.168.1.10:8000",
        detected_lan_ips=["172.17.0.2"],
    )
    assert (eff.lan_address, eff.lan_source) == ("http://nas.local:8000", "configured")


def test_public_origin_used_when_not_configured():
    # AC-6
    eff = _resolve(origin="https://photos.example.com", detected_lan_ips=["192.168.1.10"])
    assert (eff.public_address, eff.public_source) == ("https://photos.example.com", "origin")
    assert (eff.lan_address, eff.lan_source) == ("http://192.168.1.10:8000", "detected")


def test_configured_public_wins_over_origin():
    eff = _resolve(configured_public="https://a.example.com", origin="https://b.example.com")
    assert (eff.public_address, eff.public_source) == ("https://a.example.com", "configured")


def test_lan_origin_wins_over_detection():
    eff = _resolve(origin="http://192.168.1.10:8000", detected_lan_ips=["172.17.0.2"])
    assert eff == EffectiveAddresses(None, None, "http://192.168.1.10:8000", "origin")


def test_loopback_origin_is_not_used():
    # AC-6: localhost never becomes the public address; fall back to detection.
    eff = _resolve(origin="http://localhost:5173", detected_lan_ips=["192.168.1.10"])
    assert eff == EffectiveAddresses(None, None, "http://192.168.1.10:8000", "detected")


@pytest.mark.parametrize(
    "origin", ["null", "photos.example.com", "http://0.0.0.0:8000", "http://[::]:8000", "ftp://a", "http://a/path"]
)
def test_unusable_origin_is_ignored(origin):
    assert _resolve(origin=origin) == EffectiveAddresses(None, None, None, None)


def test_origin_scheme_is_case_insensitive():
    eff = _resolve(origin="HTTPS://Photos.Example.com")
    assert (eff.public_address, eff.public_source) == ("https://photos.example.com", "origin")


def test_overlong_origin_is_ignored():
    assert _resolve(origin="https://" + "a" * 10001 + ".com") == EffectiveAddresses(None, None, None, None)


def test_identical_public_and_lan_are_deduplicated():
    # AC-7
    eff = _resolve(configured_public="http://192.168.1.10:8000", detected_lan_ips=["192.168.1.10"])
    assert eff == EffectiveAddresses("http://192.168.1.10:8000", "configured", None, None)


# ---------------------------------------------------------------------------
# .server_address.json persistence (app.core.config)
# ---------------------------------------------------------------------------


@pytest.fixture
def storage(tmp_path, monkeypatch):
    root = tmp_path / "storage"
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(root))
    monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")
    reset_settings()
    yield root
    reset_settings()


@pytest.fixture
def config_warnings(monkeypatch, caplog):
    """Capture photovault.config WARNINGs (setup_logging disables propagation)."""
    monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
    with caplog.at_level(logging.WARNING, logger="photovault.config"):
        yield lambda: [
            r for r in caplog.records
            if r.name == "photovault.config" and r.levelno == logging.WARNING
        ]


def _write_raw(root: Path, data: bytes) -> Path:
    path = server_address_settings_path(str(root))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def test_missing_file_returns_empty(storage, config_warnings):
    assert get_server_address_settings() == {"public_address": "", "lan_address_override": ""}
    assert config_warnings() == []


def test_write_then_read_round_trip(storage):
    saved = set_server_address_settings(
        public_address="https://photos.example.com", lan_address_override="http://192.168.1.10:8000"
    )
    assert saved == {
        "public_address": "https://photos.example.com",
        "lan_address_override": "http://192.168.1.10:8000",
    }
    assert get_server_address_settings() == saved
    # AC-3: survives a settings reset (i.e. a restart).
    reset_settings()
    assert get_server_address_settings() == saved
    path = server_address_settings_path(str(storage))
    assert json.loads(path.read_text("utf-8")) == saved


def test_written_file_is_world_readable(storage):
    set_server_address_settings(public_address="https://a.example.com", lan_address_override="")
    path = server_address_settings_path(str(storage))
    assert stat.S_IMODE(path.stat().st_mode) == 0o644


def test_write_creates_missing_storage_root(storage):
    assert not storage.exists()
    set_server_address_settings(public_address="", lan_address_override="http://10.0.0.5")
    assert get_server_address_settings()["lan_address_override"] == "http://10.0.0.5"


def test_failed_write_keeps_original_and_leaves_no_temp_file(storage, monkeypatch):
    original = set_server_address_settings(
        public_address="https://a.example.com", lan_address_override=""
    )
    path = server_address_settings_path(str(storage))
    before = path.read_bytes()

    def boom(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr("app.core.config.os.replace", boom)
    with pytest.raises(OSError):
        set_server_address_settings(public_address="https://b.example.com", lan_address_override="")

    assert path.read_bytes() == before
    assert list(storage.glob(".server_address.*.tmp")) == []
    monkeypatch.undo()
    assert get_server_address_settings() == original


@pytest.mark.parametrize(
    "content, expected",
    [
        (b"{}", {"public_address": "", "lan_address_override": ""}),
        (
            b'{"public_address": null, "lan_address_override": null}',
            {"public_address": "", "lan_address_override": ""},
        ),
        (
            b'{"public_address": "https://photos.example.com"}',
            {"public_address": "https://photos.example.com", "lan_address_override": ""},
        ),
    ],
)
def test_missing_or_null_keys_are_empty_without_warning(storage, config_warnings, content, expected):
    _write_raw(storage, content)
    assert get_server_address_settings() == expected
    assert config_warnings() == []


def test_non_string_value_is_dropped_with_warning(storage, config_warnings):
    _write_raw(storage, b'{"public_address": 123, "lan_address_override": "http://10.0.0.5"}')
    assert get_server_address_settings() == {
        "public_address": "",
        "lan_address_override": "http://10.0.0.5",
    }
    warnings = config_warnings()
    assert len(warnings) == 1
    assert "public_address" in warnings[0].getMessage()


def test_invalid_address_value_is_dropped_with_warning(storage, config_warnings):
    _write_raw(storage, b'{"public_address": "ftp://a", "lan_address_override": "http://10.0.0.5"}')
    assert get_server_address_settings() == {
        "public_address": "",
        "lan_address_override": "http://10.0.0.5",
    }
    warnings = config_warnings()
    assert len(warnings) == 1
    assert "public_address" in warnings[0].getMessage()


@pytest.mark.parametrize(
    "content",
    [b'{"public_address": "\xb9\xab"}', b"{not json", b'["https://a.example.com"]'],
    ids=["gbk", "bad-json", "not-object"],
)
def test_unreadable_file_degrades_to_empty_with_one_warning(storage, config_warnings, content):
    _write_raw(storage, content)
    assert get_server_address_settings() == {"public_address": "", "lan_address_override": ""}
    assert len(config_warnings()) == 1


def test_identical_values_keep_public_with_warning(storage, config_warnings):
    _write_raw(storage, b'{"public_address": "http://NAS.local", "lan_address_override": "nas.local:80"}')
    assert get_server_address_settings() == {
        "public_address": "http://nas.local",
        "lan_address_override": "",
    }
    assert len(config_warnings()) == 1


def test_non_canonical_value_is_returned_canonical_without_rewrite(storage, config_warnings):
    content = b'{"public_address": "HTTPS://Photos.Example.com:443/", "lan_address_override": ""}'
    path = _write_raw(storage, content)
    assert get_server_address_settings() == {
        "public_address": "https://photos.example.com",
        "lan_address_override": "",
    }
    assert path.read_bytes() == content
    assert config_warnings() == []
