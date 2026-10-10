"""Media statistics API (design §6).

    GET /stats/media   overview / photo / video statistics of one directory

The current user is the only scope owner: ``user_id`` comes from the token and
the API accepts no user parameter. The path is validated and normalised here
(the single entry point); all aggregation happens in SQL inside
:class:`app.services.media_stats_service.MediaStatsService`.
"""

from __future__ import annotations

import asyncio
import logging
import sqlite3
from datetime import datetime, timezone
from typing import Literal, Optional

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.database import get_db
from app.core.security import get_current_user
from app.models.auth import UserInfo
from app.services import media_metadata
from app.services.media_stats_service import MediaStatsService
from app.services.stats_buckets import (
    is_valid_tz_name,
    resolve_default_tz,
    utc_offset_intervals,
)

logger = logging.getLogger("photovault.api.stats")

router = APIRouter()

_MAX_PATH_LENGTH = 1024

FFPROBE_MISSING_NOTICE = {
    "code": "ffprobe_missing",
    "message": "服务器未安装 ffprobe，部分视频的时长和拍摄时间暂不可用",
}


# ---------------------------------------------------------------------------
# Response models (§6.2)
# ---------------------------------------------------------------------------


class StatBucket(BaseModel):
    key: str
    label: str
    count: int
    share: float
    range: Optional[str] = None
    bytes: Optional[int] = None           # overview.categories / formats only
    bytes_share: Optional[float] = None   # same
    children: Optional[list["StatBucket"]] = None


class Distribution(BaseModel):
    kind: str                      # "distribution" / "ranking" / "series"
    buckets: list[StatBucket]
    unknown: int
    unknown_share: float
    other: Optional[int] = None    # kind="ranking" only
    other_share: Optional[float] = None
    total: int                     # = Σ buckets.count + (other or 0) + unknown
    total_bytes: Optional[int] = None  # overview.categories / formats only
    meta: Optional[dict[str, int]] = None


class StatsScope(BaseModel):
    path: str
    recursive: bool
    tz: str
    tz_source: Literal["config", "request", "server"]
    includes_custom_paths: bool


class Notice(BaseModel):
    code: str
    message: str


class OverviewTotal(BaseModel):
    count: int
    bytes: int


class Overview(BaseModel):
    total: OverviewTotal
    motion_photo_count: int
    categories: Distribution
    formats: Distribution


class MediaStatsResponse(BaseModel):
    schema_version: int = 1
    scope: StatsScope
    generated_at: str
    pending_metadata_count: int
    notices: list[Notice]
    overview: Overview
    photo: dict[str, Distribution]
    video: dict[str, Distribution]


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------


def _normalize_stats_path(raw: str) -> str:
    """Validate and normalise the virtual directory path (400 on failure)."""
    path = raw.strip("/")
    if len(path) > _MAX_PATH_LENGTH:
        raise HTTPException(status_code=400, detail="路径过长")
    if "\x00" in path or "\\" in path:
        raise HTTPException(status_code=400, detail="路径包含非法字符")
    if path and any(seg in ("", ".", "..") for seg in path.split("/")):
        raise HTTPException(status_code=400, detail="路径格式无效")
    return path


def _validate_tz(tz: Optional[str]) -> Optional[str]:
    """Reject a non-IANA ``tz`` with 422 ``{"code": "invalid_tz", ...}``."""
    if tz is None:
        return None
    if not is_valid_tz_name(tz):
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_tz", "message": f"无效的时区: {tz}"},
        )
    return tz


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------


@router.get(
    "/stats/media",
    response_model=MediaStatsResponse,
    response_model_exclude_none=True,
)
async def get_media_stats(
    path: str = Query("", description="Virtual directory; empty = whole library"),
    recursive: bool = Query(True, description="Include subdirectories (ignored at root)"),
    tz: Optional[str] = Query(None, max_length=64, description="Browser IANA time zone"),
    top_n: int = Query(10, ge=1, le=50, description="Ranking size"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> MediaStatsResponse:
    """Statistics of the current user's media files under ``path``."""
    settings = get_settings()
    norm_path = _normalize_stats_path(path)
    request_tz = _validate_tz(tz)

    rtz = resolve_default_tz(request_tz, settings)
    max_year = datetime.now(timezone.utc).year + 1
    intervals = await asyncio.to_thread(utc_offset_intervals, rtz, max_year)
    has_ffprobe = media_metadata.ffprobe_path() is not None
    effective_recursive = True if norm_path == "" else recursive

    service = MediaStatsService(db, settings.media_root)
    try:
        stats = await service.compute(
            current_user.id,
            current_user.username,
            norm_path,
            effective_recursive,
            intervals,
            has_ffprobe,
            top_n,
            max_year,
        )
    except (aiosqlite.Error, sqlite3.Error):
        logger.error(
            "Media stats failed user_id=%s path=%r recursive=%s",
            current_user.id,
            norm_path,
            effective_recursive,
            exc_info=True,
        )
        raise HTTPException(status_code=500, detail="统计查询失败，请稍后重试")

    video_count = next(
        b["count"] for b in stats["overview"]["categories"]["buckets"] if b["key"] == "video"
    )
    notices = []
    if not has_ffprobe and video_count > 0:
        notices.append(FFPROBE_MISSING_NOTICE)

    return MediaStatsResponse(
        scope=StatsScope(
            path=norm_path,
            recursive=effective_recursive,
            tz=rtz.name,
            tz_source=rtz.source,
            includes_custom_paths=norm_path == "",
        ),
        generated_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        pending_metadata_count=stats["pending_metadata_count"],
        notices=notices,
        overview=stats["overview"],
        photo=stats["photo"],
        video=stats["video"],
    )
