"""Media metadata extraction for the statistics feature.

Reads the capture-related metadata the statistics page needs from the stored
file itself:

- Photos: EXIF via :func:`app.services.image_utils.open_image` (Pillow +
  pillow-heif, so HEIC works). Only EXIF is read; pixels are never decoded.
- Videos: the system ``ffprobe`` (container header only).

Capture time is stored with explicit semantics: ``'local'`` (wall clock at the
place of capture, used as-is by the statistics) or ``'utc'`` (only the UTC
instant is known; the statistics convert it with the default time zone).

The 35mm-equivalent focal length comes only from EXIF FocalLengthIn35mmFilm or
from a crop factor derived from the file's own sensor tags. It never falls back
to the actual focal length.

``apply_media_metadata`` / ``apply_media_metadata_many`` are the only writers of
the metadata columns on ``file_records``; they validate every value again before
writing and never commit.

All extractors are synchronous; async callers wrap them in ``asyncio.to_thread``.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import math
import shutil
import subprocess
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import aiosqlite

from app.services.image_utils import open_image

logger = logging.getLogger("photovault.media_metadata")

CURRENT_META_VERSION = 1


@dataclass(frozen=True)
class MediaMetadata:
    capture_time: str | None = None        # 'YYYY-MM-DDTHH:MM:SS'
    capture_time_kind: str | None = None   # 'local' | 'utc'
    iso: int | None = None
    focal_35mm: float | None = None
    focal_35mm_source: str | None = None   # 'exif' | 'derived'
    focal_actual: float | None = None
    duration_sec: float | None = None
    f_number: float | None = None
    exposure_time: float | None = None
    camera_make: str | None = None
    camera_model: str | None = None
    lens_model: str | None = None
    status: str = "ok"                     # 'ok' | 'error' | 'no_ffprobe' | 'timeout'


UPLOAD_FFPROBE_TIMEOUT = 5.0     # seconds, inside the upload-complete request
BACKFILL_FFPROBE_TIMEOUT = 15.0  # seconds, background backfill

# ---------------------------------------------------------------------------
# Value ranges (design §3.2)
# ---------------------------------------------------------------------------

_ISO_MAX = 10_000_000
_FOCAL_MAX = 5000.0
_DURATION_MAX = 604_800.0
_F_NUMBER_MAX = 128.0
_EXPOSURE_MAX = 3600.0
_TEXT_MAX = 128

# Crop factor sanity range: medium format to 1/3" phone sensors.
_CROP_FACTOR_MIN = 0.5
_CROP_FACTOR_MAX = 10.0
_FULL_FRAME_DIAGONAL_MM = 43.27
_FOCAL_PLANE_UNIT_MM = {2: 25.4, 3: 10.0, 4: 1.0}

# Container "zero" dates written by cameras that never set the clock.
_ZERO_DATES = {"1904-01-01T00:00:00", "1970-01-01T00:00:00"}

# EXIF tags
_TAG_MAKE = 271
_TAG_MODEL = 272
_TAG_EXPOSURE_TIME = 33434
_TAG_F_NUMBER = 33437
_TAG_ISO = 34855
_TAG_ISO_FALLBACK = 34867
_TAG_DATETIME_ORIGINAL = 36867
_TAG_DATETIME_DIGITIZED = 36868
_TAG_FOCAL_LENGTH = 37386
_TAG_PIXEL_X = 40962
_TAG_PIXEL_Y = 40963
_TAG_FOCAL_PLANE_X_RES = 41486
_TAG_FOCAL_PLANE_Y_RES = 41487
_TAG_FOCAL_PLANE_UNIT = 41488
_TAG_FOCAL_35MM = 41989
_TAG_LENS_MODEL = 42036

_VIDEO_MAKE_KEYS = ("com.apple.quicktime.make", "com.android.manufacturer", "make")
_VIDEO_MODEL_KEYS = ("com.apple.quicktime.model", "com.android.model", "model")


# ---------------------------------------------------------------------------
# Normalisation helpers
# ---------------------------------------------------------------------------


def normalize_text(value: object) -> str | None:
    """Normalise a free-text EXIF / container string (design §4.5).

    Non-``str`` values (Pillow may return ``bytes``) are decoded as UTF-8 with
    replacement. NUL bytes are removed, runs of whitespace collapse to a single
    space, the result is trimmed and truncated to 128 characters. Empty -> None.
    Case is preserved; grouping folds case later.
    """
    if value is None:
        return None
    if isinstance(value, (bytes, bytearray, memoryview)):
        text = bytes(value).decode("utf-8", errors="replace")
    elif isinstance(value, str):
        text = value
    else:
        text = str(value)
    text = " ".join(text.replace("\x00", "").split())
    text = text[:_TEXT_MAX].strip()
    return text or None


def _to_float(value: Any) -> float | None:
    """Convert an EXIF/ffprobe value to float; None when not convertible."""
    if value is None:
        return None
    if isinstance(value, (tuple, list)):
        if not value:
            return None
        value = value[0]
    try:
        result = float(value)
    except (TypeError, ValueError, ZeroDivisionError, OverflowError):
        return None
    return result


def _clean_float(value: Any, upper: float, ndigits: int | None) -> float | None:
    """Validate a positive finite number in (0, upper], optionally rounded."""
    number = _to_float(value)
    if number is None or not math.isfinite(number):
        return None
    if ndigits is not None:
        number = round(number, ndigits)
    if number <= 0 or number > upper:
        return None
    return number


def _clean_iso(value: Any) -> int | None:
    """ISO speed: first positive integer (tuples allowed), in [1, 10,000,000]."""
    if value is None:
        return None
    candidates = value if isinstance(value, (tuple, list)) else (value,)
    for candidate in candidates:
        number = _to_float(candidate)
        if number is None or not math.isfinite(number):
            continue
        iso = int(number)
        if iso >= 1:
            return iso if iso <= _ISO_MAX else None
    return None


def _clean_capture(
    capture_time: str | None, kind: str | None
) -> tuple[str | None, str | None]:
    """Keep only 19-character timestamps with a known kind."""
    if (
        not isinstance(capture_time, str)
        or len(capture_time) != 19
        or kind not in ("local", "utc")
    ):
        return None, None
    try:
        datetime.strptime(capture_time, "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return None, None
    return capture_time, kind


def _sanitize(meta: MediaMetadata) -> MediaMetadata:
    """Re-validate every field before writing (the writer owns the invariants)."""
    capture_time, kind = _clean_capture(meta.capture_time, meta.capture_time_kind)
    focal_35mm = _clean_float(meta.focal_35mm, _FOCAL_MAX, 1)
    source = meta.focal_35mm_source if meta.focal_35mm_source in ("exif", "derived") else None
    if focal_35mm is None or source is None:
        focal_35mm, source = None, None
    return dataclasses.replace(
        meta,
        capture_time=capture_time,
        capture_time_kind=kind,
        iso=_clean_iso(meta.iso),
        focal_35mm=focal_35mm,
        focal_35mm_source=source,
        focal_actual=_clean_float(meta.focal_actual, _FOCAL_MAX, 1),
        duration_sec=_clean_float(meta.duration_sec, _DURATION_MAX, 3),
        f_number=_clean_float(meta.f_number, _F_NUMBER_MAX, 2),
        exposure_time=_clean_float(meta.exposure_time, _EXPOSURE_MAX, None),
        camera_make=normalize_text(meta.camera_make),
        camera_model=normalize_text(meta.camera_model),
        lens_model=normalize_text(meta.lens_model),
    )


def _format_wall_clock(dt: datetime) -> str:
    """Format a datetime as 'YYYY-MM-DDTHH:MM:SS' (no suffix, seconds precision)."""
    return dt.replace(tzinfo=None, microsecond=0).isoformat(timespec="seconds")


# ---------------------------------------------------------------------------
# Capture time
# ---------------------------------------------------------------------------


def _parse_exif_datetime(value: Any) -> str | None:
    """Parse an EXIF 'YYYY:MM:DD HH:MM:SS' value; None when absent/invalid."""
    if value is None:
        return None
    if isinstance(value, (bytes, bytearray)):
        value = bytes(value).decode("utf-8", errors="replace")
    text = str(value).replace("\x00", "").strip()
    if not text:
        return None
    try:
        dt = datetime.strptime(text, "%Y:%m:%d %H:%M:%S")
    except ValueError:
        return None
    return _format_wall_clock(dt)


def resolve_capture_time(
    file_value: tuple[str, str] | None,
    client_exif_time: str | None,
    media_type: str | None,
) -> tuple[str | None, str | None]:
    """Decide the stored capture time and its kind (design §4.4).

    Priority: the file's own value, then the client ``exif_time``, else
    ``(None, None)``. mtime / created_at / upload time are never used.

    Client value rules: photos keep the wall clock as ``'local'``. Videos
    without an offset or with offset 0 are Android UTC values -> ``'utc'``;
    a non-zero offset means a local wall clock -> ``'local'``.
    """
    if file_value and file_value[0]:
        return file_value[0], file_value[1]

    if client_exif_time is None:
        return None, None
    if isinstance(client_exif_time, datetime):
        dt = client_exif_time
    else:
        text = str(client_exif_time).strip()
        if not text:
            return None, None
        try:
            dt = datetime.fromisoformat(text)
        except ValueError:
            return None, None

    if media_type == "video":
        offset = dt.utcoffset()
        if offset is None or offset == timedelta(0):
            return _format_wall_clock(dt), "utc"
        return _format_wall_clock(dt), "local"
    return _format_wall_clock(dt), "local"


# ---------------------------------------------------------------------------
# Photos
# ---------------------------------------------------------------------------


def derive_crop_factor(
    exif_ifd: Mapping, image_size: tuple[int, int]
) -> float | None:
    """Crop factor from the file's own focal-plane tags (design §4.3).

    Requires FocalPlaneX/YResolution (> 0, finite), a known
    FocalPlaneResolutionUnit, and PixelX/YDimension equal to ``image_size``
    (or swapped) so resized/cropped exports are excluded. Returns
    ``43.27 / sensor_diagonal`` when it lies in [0.5, 10], otherwise None.
    """
    try:
        x_res = _to_float(exif_ifd.get(_TAG_FOCAL_PLANE_X_RES))
        y_res = _to_float(exif_ifd.get(_TAG_FOCAL_PLANE_Y_RES))
        if x_res is None or y_res is None:
            return None
        if not (math.isfinite(x_res) and math.isfinite(y_res)) or x_res <= 0 or y_res <= 0:
            return None

        unit = exif_ifd.get(_TAG_FOCAL_PLANE_UNIT)
        if unit is None:
            return None
        unit_mm = _FOCAL_PLANE_UNIT_MM.get(int(unit))
        if unit_mm is None:
            return None

        pixel_x = exif_ifd.get(_TAG_PIXEL_X)
        pixel_y = exif_ifd.get(_TAG_PIXEL_Y)
        if pixel_x is None or pixel_y is None:
            return None
        pixel_x, pixel_y = int(pixel_x), int(pixel_y)
        if pixel_x <= 0 or pixel_y <= 0:
            return None
        size = (int(image_size[0]), int(image_size[1]))
        if size != (pixel_x, pixel_y) and size != (pixel_y, pixel_x):
            return None

        width_mm = pixel_x / x_res * unit_mm
        height_mm = pixel_y / y_res * unit_mm
        diagonal = math.hypot(width_mm, height_mm)
        if not math.isfinite(diagonal) or diagonal <= 0:
            return None
        crop = _FULL_FRAME_DIAGONAL_MM / diagonal
    except (TypeError, ValueError, ZeroDivisionError, OverflowError):
        return None

    if not math.isfinite(crop) or crop < _CROP_FACTOR_MIN or crop > _CROP_FACTOR_MAX:
        return None
    return crop


def extract_image_metadata(path: str) -> MediaMetadata:
    """Read photo metadata from EXIF (design §4.2). May raise on unreadable files."""
    from PIL.ExifTags import IFD

    with open_image(path) as img:
        exif = img.getexif()
        image_size = img.size
        try:
            exif_ifd = dict(exif.get_ifd(IFD.Exif))
        except Exception:  # noqa: BLE001 - malformed sub-IFD
            exif_ifd = {}

    # Exif IFD first, falling back to the root IFD (same as _extract_focal_length).
    tags: dict[int, Any] = dict(exif)
    tags.update({k: v for k, v in exif_ifd.items() if v is not None})

    capture_time = _parse_exif_datetime(tags.get(_TAG_DATETIME_ORIGINAL))
    if capture_time is None:
        capture_time = _parse_exif_datetime(tags.get(_TAG_DATETIME_DIGITIZED))

    iso = _clean_iso(tags.get(_TAG_ISO))
    if iso is None:
        iso = _clean_iso(tags.get(_TAG_ISO_FALLBACK))

    focal_actual = _clean_float(tags.get(_TAG_FOCAL_LENGTH), _FOCAL_MAX, 1)
    focal_35mm = _clean_float(tags.get(_TAG_FOCAL_35MM), _FOCAL_MAX, 1)
    focal_source: str | None = "exif" if focal_35mm is not None else None
    if focal_35mm is None and focal_actual is not None:
        # Never fall back to the actual focal length itself (AC17).
        crop = derive_crop_factor(tags, image_size)
        if crop is not None:
            focal_35mm = _clean_float(focal_actual * crop, _FOCAL_MAX, 1)
            focal_source = "derived" if focal_35mm is not None else None

    return MediaMetadata(
        capture_time=capture_time,
        capture_time_kind="local" if capture_time else None,
        iso=iso,
        focal_35mm=focal_35mm,
        focal_35mm_source=focal_source,
        focal_actual=focal_actual,
        f_number=_clean_float(tags.get(_TAG_F_NUMBER), _F_NUMBER_MAX, 2),
        exposure_time=_clean_float(tags.get(_TAG_EXPOSURE_TIME), _EXPOSURE_MAX, None),
        camera_make=normalize_text(tags.get(_TAG_MAKE)),
        camera_model=normalize_text(tags.get(_TAG_MODEL)),
        lens_model=normalize_text(tags.get(_TAG_LENS_MODEL)),
        status="ok",
    )


# ---------------------------------------------------------------------------
# Videos
# ---------------------------------------------------------------------------

_ffprobe_cached: str | None = None
_ffprobe_warned = False


def ffprobe_path() -> str | None:
    """Locate ffprobe. Only a found path is cached; WARNING once per process."""
    global _ffprobe_cached, _ffprobe_warned
    if _ffprobe_cached:
        return _ffprobe_cached
    found = shutil.which("ffprobe")
    if found:
        _ffprobe_cached = found
    elif not _ffprobe_warned:
        _ffprobe_warned = True
        logger.warning(
            "ffprobe not found on PATH; video duration/capture time will be unavailable"
        )
    return found


def _reset_ffprobe_cache() -> None:
    """Reset the ffprobe lookup cache and warning flag (tests only)."""
    global _ffprobe_cached, _ffprobe_warned
    _ffprobe_cached = None
    _ffprobe_warned = False


def _parse_container_time(value: Any, kind: str) -> str | None:
    """Parse an ffprobe date tag into a 19-char string (local wall clock or UTC)."""
    if value is None:
        return None
    text = str(value).replace("\x00", "").strip()
    if not text:
        return None
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if kind == "utc" and dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc)
    result = _format_wall_clock(dt)
    if result in _ZERO_DATES:
        return None
    return result


def _first_text(tags: Mapping[str, Any], keys: Sequence[str]) -> str | None:
    for key in keys:
        text = normalize_text(tags.get(key))
        if text:
            return text
    return None


def parse_ffprobe_json(data: dict) -> MediaMetadata:
    """Map ffprobe JSON output to metadata (design §4.4, §4.7)."""
    fmt = data.get("format") or {}
    if not isinstance(fmt, Mapping):
        fmt = {}
    raw_tags = fmt.get("tags") or {}
    tags = {str(k).lower(): v for k, v in raw_tags.items()} if isinstance(raw_tags, Mapping) else {}

    capture_time: str | None = _parse_container_time(
        tags.get("com.apple.quicktime.creationdate"), "local"
    )
    kind: str | None = "local" if capture_time else None
    if capture_time is None:
        capture_time = _parse_container_time(tags.get("creation_time"), "utc")
        kind = "utc" if capture_time else None

    duration = _clean_float(fmt.get("duration"), _DURATION_MAX, 3)
    if duration is None:
        stream_durations = []
        for stream in data.get("streams") or []:
            if not isinstance(stream, Mapping) or stream.get("codec_type") != "video":
                continue
            value = _clean_float(stream.get("duration"), _DURATION_MAX, 3)
            if value is not None:
                stream_durations.append(value)
        duration = max(stream_durations) if stream_durations else None

    return MediaMetadata(
        capture_time=capture_time,
        capture_time_kind=kind,
        duration_sec=duration,
        camera_make=_first_text(tags, _VIDEO_MAKE_KEYS),
        camera_model=_first_text(tags, _VIDEO_MODEL_KEYS),
        status="ok",
    )


def extract_video_metadata(path: str, timeout: float) -> MediaMetadata:
    """Probe a video with ffprobe; never raises for probe failures."""
    executable = ffprobe_path()
    if not executable:
        return MediaMetadata(status="no_ffprobe")

    cmd = [
        executable,
        "-v", "error",
        "-print_format", "json",
        "-show_entries", "format=duration:format_tags:stream=codec_type,duration",
        path,
    ]
    try:
        proc = subprocess.run(cmd, timeout=timeout, capture_output=True)
    except subprocess.TimeoutExpired:
        logger.debug("ffprobe timed out after %.1fs for %s", timeout, path)
        return MediaMetadata(status="timeout")
    except OSError as exc:
        logger.debug("ffprobe could not run for %s: %s", path, exc)
        return MediaMetadata(status="error")

    if proc.returncode != 0:
        logger.debug(
            "ffprobe failed for %s (rc=%s): %s",
            path,
            proc.returncode,
            (proc.stderr or b"")[:200],
        )
        return MediaMetadata(status="error")
    try:
        data = json.loads(proc.stdout or b"")
    except (ValueError, UnicodeDecodeError) as exc:
        logger.debug("ffprobe returned invalid JSON for %s: %s", path, exc)
        return MediaMetadata(status="error")
    if not isinstance(data, dict):
        return MediaMetadata(status="error")
    return parse_ffprobe_json(data)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def extract_media_metadata(
    path: str,
    media_type: str | None,
    client_exif_time: str | None,
    *,
    ffprobe_timeout: float,
) -> MediaMetadata:
    """Extract metadata for a stored file. Never raises.

    Only ``media_type == 'video'`` goes to ffprobe; everything else (including
    None) is treated as a photo. The capture time falls back to the client
    value whenever the file has none, including on failure.
    """
    try:
        if media_type == "video":
            meta = extract_video_metadata(path, ffprobe_timeout)
        else:
            meta = extract_image_metadata(path)
        file_value = (
            (meta.capture_time, meta.capture_time_kind) if meta.capture_time else None
        )
        capture_time, kind = resolve_capture_time(file_value, client_exif_time, media_type)
        return dataclasses.replace(
            meta, capture_time=capture_time, capture_time_kind=kind
        )
    except Exception as exc:  # noqa: BLE001 - contract: never raise
        logger.debug("Metadata extraction failed for %s: %s", path, exc)
        try:
            capture_time, kind = resolve_capture_time(None, client_exif_time, media_type)
        except Exception:  # noqa: BLE001
            capture_time, kind = None, None
        return MediaMetadata(
            status="error", capture_time=capture_time, capture_time_kind=kind
        )


# ---------------------------------------------------------------------------
# Writers (the only code that writes the metadata columns)
# ---------------------------------------------------------------------------

_FULL_SET_SQL = (
    "capture_time = ?, capture_time_kind = ?, iso = ?, focal_35mm = ?, "
    "focal_35mm_source = ?, focal_actual = ?, duration_sec = ?, f_number = ?, "
    "exposure_time = ?, camera_make = ?, camera_model = ?, lens_model = ?, "
    "meta_version = ?, meta_status = ?, meta_scanned_at = ?"
)
_TIMEOUT_SET_SQL = (
    "capture_time = ?, capture_time_kind = ?, meta_status = 'timeout', "
    "meta_scanned_at = ?"
)


def _set_clause(meta: MediaMetadata) -> tuple[str, tuple]:
    """Pick the SET clause by status. 'timeout' keeps the row pending."""
    clean = _sanitize(meta)
    scanned_at = datetime.now(timezone.utc).isoformat()
    if clean.status == "timeout":
        return _TIMEOUT_SET_SQL, (clean.capture_time, clean.capture_time_kind, scanned_at)
    return _FULL_SET_SQL, (
        clean.capture_time,
        clean.capture_time_kind,
        clean.iso,
        clean.focal_35mm,
        clean.focal_35mm_source,
        clean.focal_actual,
        clean.duration_sec,
        clean.f_number,
        clean.exposure_time,
        clean.camera_make,
        clean.camera_model,
        clean.lens_model,
        CURRENT_META_VERSION,
        clean.status,
        scanned_at,
    )


async def apply_media_metadata(
    db: aiosqlite.Connection, file_id: int, meta: MediaMetadata
) -> None:
    """Write metadata for a row created by the same request. Does not commit."""
    set_sql, params = _set_clause(meta)
    await db.execute(
        f"UPDATE file_records SET {set_sql} WHERE id = ?",
        (*params, file_id),
    )


async def apply_media_metadata_many(
    db: aiosqlite.Connection,
    rows: Sequence[tuple[int, str, MediaMetadata]],
) -> int:
    """Write backfill results with an optimistic guard. Does not commit.

    Each row is ``(file_id, file_path read at SELECT time, meta)``. A row that
    was moved to the trash or deleted while being extracted no longer matches
    and is skipped. Returns the number of rows actually updated.
    """
    updated = 0
    for file_id, file_path, meta in rows:
        set_sql, params = _set_clause(meta)
        cursor = await db.execute(
            f"UPDATE file_records SET {set_sql} "
            "WHERE id = ? AND file_path = ? AND deleted_at IS NULL AND purged_at IS NULL",
            (*params, file_id, file_path),
        )
        if cursor.rowcount == 0:
            logger.debug(
                "Backfill skipped file_id=%d: moved or deleted during extraction",
                file_id,
            )
        else:
            updated += cursor.rowcount
    return updated
