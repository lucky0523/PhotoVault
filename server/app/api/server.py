"""Server info API endpoints.

Endpoints:
- GET /api/v1/server/info  (LAN IP + port for QR code generation)
"""

from __future__ import annotations

import logging
import socket
from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.core.config import get_settings
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


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/server/info", response_model=ServerInfoResponse)
async def get_server_info(
    current_user: UserInfo = Depends(get_current_user),
) -> ServerInfoResponse:
    """Return the server's LAN IP addresses and port.

    Used by the web UI to render a QR code that mobile clients can scan.
    Uses a UDP socket probe: connecting a UDP socket to a remote address
    does not send any packets — it only lets the kernel pick the source
    address for that route, which is the primary LAN IP.
    """
    lan_ips: List[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1.0)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            lan_ips.append(ip)
        finally:
            s.close()
    except Exception:
        pass

    lan_ips = [
        ip
        for ip in lan_ips
        if not ip.startswith("127.") and not ip.startswith("169.254.")
    ]

    settings = get_settings()
    return ServerInfoResponse(lan_ips=lan_ips, port=settings.server_port)
