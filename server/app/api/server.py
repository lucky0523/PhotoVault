"""Server info API endpoints.

Endpoints:
- GET /api/v1/server/info   (LAN IP + port for QR code generation)
- GET /api/v1/server/about  (version / runtime / storage info for 设置 → 关于服务端)
"""

from __future__ import annotations

import logging
import platform
import socket
import sys
from typing import List, Optional

import fastapi
from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app import __version__
from app.core.config import get_settings
from app.core.network import detect_lan_ips
from app.core.runtime import get_started_at, get_uptime_seconds
from app.models.auth import UserInfo
from app.core.security import get_current_user

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
