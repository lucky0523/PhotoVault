"""Server info API endpoints.

Endpoints:
- GET /api/v1/server/info              (LAN IP + port; kept unchanged for older web builds)
- GET /api/v1/server/about             (version / runtime / storage info for 设置 → 关于服务端)
- GET /api/v1/server/connect-qr        (服务器二维码 v1 payload + effective addresses; any logged-in user)
- GET /api/v1/server/address-settings  (configured public / LAN addresses; admin only)
- PUT /api/v1/server/address-settings  (save them to {storage_root}/.server_address.json; admin only)

服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。
The payload is built only by ``app.core.server_address.encode_connect_payload``.
"""

from __future__ import annotations

import asyncio
import logging
import platform
import socket
import sys
from typing import List, Literal, Optional

import fastapi
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app import __version__
from app.core.config import (
    get_server_address_settings,
    get_settings,
    server_address_settings_path,
    set_server_address_settings,
)
from app.core.network import detect_lan_ips
from app.core.runtime import get_started_at, get_uptime_seconds
from app.core.server_address import (
    ADDRESS_ERROR_MESSAGES,
    PAYLOAD_VERSION,
    AddressError,
    detected_lan_address,
    encode_connect_payload,
    resolve_effective_addresses,
    same_as_other_message,
    validate_address_pair,
)
from app.models.auth import UserInfo
from app.core.security import get_current_user, require_admin

logger = logging.getLogger("photovault.api.server")

router = APIRouter()


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------


class ServerInfoResponse(BaseModel):
    """Server network info for QR code generation.

    Exposes the machine's non-loopback IPv4 addresses and the configured
    server port so the web UI can render a QR code pointing at a reachable
    address (instead of localhost when debugging locally).
    """

    lan_ips: List[str]
    port: int


class StorageInfo(BaseModel):
    """Storage paths and disk usage. Admin-only.

    The four paths are configured independently of each other; ``storage_root``
    is reported as well because it is the fallback base for whichever ones were
    left unset. Disk usage describes the volume holding ``media_root``.
    """

    storage_root: str
    media_root: str
    database_path: str
    log_dir: str
    models_root: str
    total_gb: float
    used_gb: float
    available_gb: float


class ConfigInfo(BaseModel):
    """Effective runtime configuration. Admin-only."""

    max_users: int
    allow_registration: bool
    chunk_size_mb: int
    session_expire_days: int
    trash_retention_days: int
    access_token_expire_hours: int
    refresh_token_expire_days: int
    log_level: str
    enable_place: bool
    enable_scene: bool
    enable_face: bool


class ServerAboutResponse(BaseModel):
    """Everything the 关于服务端 page shows.

    Split into "everyone" fields (version, runtime, host platform) and two
    optional admin-only blocks. Absolute paths and the effective configuration
    are infrastructure details a regular user has no reason to see, so
    ``storage`` and ``config`` are ``None`` for non-admins and the page simply
    omits those sections.
    """

    # Identity
    name: str
    version: str
    api_version: str

    # Runtime
    started_at: str
    uptime_seconds: float
    python_version: str
    fastapi_version: str
    platform: str
    machine: str
    hostname: str

    # Network
    port: int
    lan_ips: List[str]

    # Admin-only detail
    storage: Optional[StorageInfo] = None
    config: Optional[ConfigInfo] = None


class ConnectQrResponse(BaseModel):
    """What the 服务器二维码 dialog renders.

    ``payload`` is the exact string to encode into the QR code (``None`` when
    neither address is available). The addresses and their sources are shown
    as text next to it. Exactly these six fields — no paths, tokens or ids.
    """

    version: int
    payload: Optional[str]
    public_address: Optional[str]
    public_source: Optional[Literal["configured", "origin"]]
    lan_address: Optional[str]
    lan_source: Optional[Literal["configured", "origin", "detected"]]


class ServerAddressSettingsResponse(BaseModel):
    """Configured (not effective) addresses; "" means not configured.

    ``detected_lan_address`` is the auto-detected fallback, shown only as a
    placeholder / hint on the settings page.
    """

    public_address: str
    lan_address_override: str
    detected_lan_address: Optional[str]


class ServerAddressSettingsUpdate(BaseModel):
    """Full replacement: both keys are required, ``null`` / blank clears a value.

    No ``max_length`` on purpose, so an over-long value gets the field-level
    TOO_LONG message instead of a generic validation error.
    """

    public_address: Optional[str]
    lan_address_override: Optional[str]


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/server/info", response_model=ServerInfoResponse)
async def get_server_info(
    current_user: UserInfo = Depends(get_current_user),
) -> ServerInfoResponse:
    """Return the server's LAN IP addresses and port.

    Used by the web UI to render a QR code that mobile clients can scan.
    """
    settings = get_settings()
    return ServerInfoResponse(lan_ips=detect_lan_ips(), port=settings.server_port)


@router.get("/server/about", response_model=ServerAboutResponse)
async def get_server_about(
    current_user: UserInfo = Depends(get_current_user),
) -> ServerAboutResponse:
    """Return version, runtime and host information about this server.

    Backs the 设置 → 关于服务端 page. Storage paths and the effective
    configuration are only included for admin users.
    """
    from app.services.background_tasks import get_disk_stats, refresh_disk_stats

    settings = get_settings()

    storage: Optional[StorageInfo] = None
    config: Optional[ConfigInfo] = None

    if current_user.is_admin:
        # The periodic monitor refreshes hourly and starts at all-zeros, so read
        # the disk directly here and fall back to the cached values if that
        # fails (unmounted volume, permission error).
        try:
            disk = refresh_disk_stats(settings.media_root)
        except Exception:
            logger.warning("Could not refresh disk stats for /server/about", exc_info=True)
            disk = get_disk_stats()

        storage = StorageInfo(
            storage_root=settings.storage_root,
            media_root=settings.media_root,
            database_path=settings.database_path,
            log_dir=settings.log_dir,
            models_root=settings.models_root,
            total_gb=disk.get("total_gb", 0.0),
            used_gb=disk.get("used_gb", 0.0),
            available_gb=disk.get("available_gb", 0.0),
        )
        config = ConfigInfo(
            max_users=settings.max_users,
            allow_registration=settings.allow_registration,
            chunk_size_mb=settings.chunk_size_mb,
            session_expire_days=settings.session_expire_days,
            trash_retention_days=settings.trash_retention_days,
            access_token_expire_hours=settings.access_token_expire_hours,
            refresh_token_expire_days=settings.refresh_token_expire_days,
            log_level=settings.log_level,
            enable_place=settings.enable_place,
            enable_scene=settings.enable_scene,
            enable_face=settings.enable_face,
        )

    return ServerAboutResponse(
        name="PhotoVault",
        version=__version__,
        api_version="v1",
        started_at=get_started_at().isoformat(),
        uptime_seconds=round(get_uptime_seconds(), 1),
        python_version=platform.python_version() or sys.version.split()[0],
        fastapi_version=fastapi.__version__,
        platform=f"{platform.system()} {platform.release()}".strip(),
        machine=platform.machine(),
        hostname=socket.gethostname(),
        port=settings.server_port,
        lan_ips=detect_lan_ips(),
        storage=storage,
        config=config,
    )


@router.get("/server/connect-qr", response_model=ConnectQrResponse)
async def get_connect_qr(
    origin: Optional[str] = None,
    current_user: UserInfo = Depends(get_current_user),
) -> ConnectQrResponse:
    """Return the 服务器二维码 v1 payload and the effective addresses.

    ``origin`` is the browser's ``window.location.origin``. It is untrusted and
    has no length limit here: anything that is not an explicit, valid
    ``http(s)://`` address is ignored rather than rejected.
    """
    settings = get_settings()
    configured = get_server_address_settings()
    # detect_lan_ips may run subprocesses (up to 2 s each); keep it off the loop.
    detected = await asyncio.to_thread(detect_lan_ips)
    effective = resolve_effective_addresses(
        configured_public=configured["public_address"],
        configured_lan=configured["lan_address_override"],
        origin=origin,
        detected_lan_ips=detected,
        server_port=settings.server_port,
    )
    return ConnectQrResponse(
        version=PAYLOAD_VERSION,
        payload=encode_connect_payload(effective.public_address, effective.lan_address),
        public_address=effective.public_address,
        public_source=effective.public_source,
        lan_address=effective.lan_address,
        lan_source=effective.lan_source,
    )


async def _address_settings_response(saved: dict[str, str]) -> ServerAddressSettingsResponse:
    detected = await asyncio.to_thread(detect_lan_ips)
    return ServerAddressSettingsResponse(
        public_address=saved["public_address"],
        lan_address_override=saved["lan_address_override"],
        detected_lan_address=detected_lan_address(detected, get_settings().server_port),
    )


@router.get("/server/address-settings", response_model=ServerAddressSettingsResponse)
async def get_address_settings(
    current_user: UserInfo = Depends(require_admin),
) -> ServerAddressSettingsResponse:
    """Return the configured server addresses for the 设置 → 服务器地址 page."""
    return await _address_settings_response(get_server_address_settings())


@router.put("/server/address-settings", response_model=ServerAddressSettingsResponse)
async def update_address_settings(
    body: ServerAddressSettingsUpdate,
    current_user: UserInfo = Depends(require_admin),
) -> ServerAddressSettingsResponse:
    """Validate and save both server addresses (full replacement).

    Any invalid field → 422 ``{"code": "invalid_address", "message", "fields"}``
    and nothing is written. Values are stored in canonical form.
    """
    pub_check, lan_check = validate_address_pair(body.public_address, body.lan_address_override)

    fields: dict[str, dict[str, str]] = {}
    for field, check in (("public_address", pub_check), ("lan_address_override", lan_check)):
        if check.error is None:
            continue
        if check.error is AddressError.SAME_AS_OTHER:
            message = same_as_other_message(field)
        else:
            message = ADDRESS_ERROR_MESSAGES[check.error]
        fields[field] = {"code": check.error.value, "message": message}
    if fields:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "invalid_address",
                "message": next(iter(fields.values()))["message"],
                "fields": fields,
            },
        )

    pub = pub_check.canonical
    lan = lan_check.canonical
    try:
        # fsync can stall on a NAS spinning disk; keep it off the event loop.
        saved = await asyncio.to_thread(
            set_server_address_settings,
            public_address=pub or "",
            lan_address_override=lan or "",
        )
    except OSError:
        logger.error(
            "Could not write %s",
            server_address_settings_path(get_settings().storage_root),
            exc_info=True,
        )
        raise HTTPException(status_code=500, detail="保存服务器地址失败")

    logger.info(
        "Server addresses updated by %s: public_address=%s lan_address_override=%s",
        current_user.username,
        pub or "(none)",
        lan or "(none)",
    )
    return await _address_settings_response(saved)
