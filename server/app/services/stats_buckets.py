"""Bucket constants and pure helpers for the media statistics (design §5.3–§5.6).

Single source of truth for:

- the five numeric bucket specs (ISO, 35mm-equivalent focal length, aperture,
  shutter, video duration) and the SQL ``CASE`` expression that assigns a row to
  a bucket (:func:`bucket_case_sql`);
- the signed scene EV spec (:data:`SCENE_EV_SPEC`) and its ``summary`` dict
  (:func:`ev_summary`);
- the fixed weekday / hour enumerations;
- format grouping (:data:`FORMAT_GROUPS`, :func:`format_group`);
- integer per-mille share allocation (:func:`allocate_shares`);
- camera / lens rankings (:func:`camera_display_name`, :func:`build_ranking`);
- monthly / yearly trend series on a shared axis (:func:`build_trends`);
- per-day counts by local capture date (:func:`build_daily`);
- the default time zone (:func:`resolve_default_tz`) and the UTC -> local
  offset interval table used by the statistics SQL (:func:`utc_offset_intervals`).

Everything here is pure (no database access). The helpers that build response
fragments return plain ``dict`` objects shaped exactly like the Pydantic
``Distribution`` / ``StatBucket`` models in ``app.api.stats``; the API layer
validates them into those models.
"""

from __future__ import annotations

import functools
import logging
import os
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone, tzinfo
from typing import TYPE_CHECKING, Any, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.services.media_metadata import normalize_text

if TYPE_CHECKING:  # pragma: no cover
    from app.core.config import Settings

logger = logging.getLogger("photovault.stats")

UNKNOWN_KEY = "__unknown"


# ---------------------------------------------------------------------------
# Numeric bucket specs (§5.4)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Bucket:
    key: str
    label: str
    upper: float | None      # None means +inf
    range: str               # text shown in the「区间」column


@dataclass(frozen=True)
class BucketSpec:
    column: str              # stats_scope column name (code constant, never user input)
    mode: Literal["upper_inclusive", "lower_inclusive"]   # (lo, hi] or [lo, hi)
    buckets: tuple[Bucket, ...]
    rel_tolerance: float = 0.0   # bound threshold = upper * (1 + rel_tolerance)
    signed: bool = False     # True: 0 and negatives are valid values, only NULL is unknown


def _validate_spec(spec: BucketSpec) -> BucketSpec:
    """Assert a spec describes contiguous, non-overlapping buckets.

    ``upper`` strictly increasing with the last one ``None``; keys unique;
    ``rel_tolerance`` only with ``upper_inclusive`` and smaller than the smallest
    relative gap between adjacent thresholds.
    """
    if not spec.buckets:
        raise AssertionError(f"{spec.column}: no buckets")
    keys = [b.key for b in spec.buckets]
    if len(set(keys)) != len(keys) or UNKNOWN_KEY in keys:
        raise AssertionError(f"{spec.column}: bucket keys must be unique")
    if spec.buckets[-1].upper is not None:
        raise AssertionError(f"{spec.column}: last bucket must be open-ended")
    uppers = [b.upper for b in spec.buckets[:-1]]
    if any(u is None for u in uppers):
        raise AssertionError(f"{spec.column}: only the last bucket may be open-ended")
    for lo, hi in zip(uppers, uppers[1:]):
        if not lo < hi:
            raise AssertionError(f"{spec.column}: thresholds must strictly increase")
    if spec.mode not in ("upper_inclusive", "lower_inclusive"):
        raise AssertionError(f"{spec.column}: unknown mode {spec.mode!r}")
    if spec.rel_tolerance < 0:
        raise AssertionError(f"{spec.column}: rel_tolerance must be >= 0")
    if spec.signed and spec.rel_tolerance != 0:
        raise AssertionError(f"{spec.column}: rel_tolerance is not allowed with signed")
    if spec.rel_tolerance:
        if spec.mode != "upper_inclusive":
            raise AssertionError(
                f"{spec.column}: rel_tolerance is only allowed with upper_inclusive"
            )
        if uppers and uppers[0] <= 0:
            raise AssertionError(f"{spec.column}: thresholds must be positive")
        min_gap = min((hi / lo - 1 for lo, hi in zip(uppers, uppers[1:])), default=1.0)
        if not spec.rel_tolerance < min_gap:
            raise AssertionError(
                f"{spec.column}: rel_tolerance must be smaller than the threshold gaps"
            )
    return spec


ISO_SPEC = _validate_spec(BucketSpec(
    column="iso",
    mode="upper_inclusive",
    buckets=(
        Bucket("iso_le_50", "≤50", 50, "(0, 50]"),
        Bucket("iso_51_100", "51–100", 100, "(50, 100]"),
        Bucket("iso_101_200", "101–200", 200, "(100, 200]"),
        Bucket("iso_201_400", "201–400", 400, "(200, 400]"),
        Bucket("iso_401_800", "401–800", 800, "(400, 800]"),
        Bucket("iso_801_1600", "801–1600", 1600, "(800, 1600]"),
        Bucket("iso_1601_3200", "1601–3200", 3200, "(1600, 3200]"),
        Bucket("iso_3201_6400", "3201–6400", 6400, "(3200, 6400]"),
        Bucket("iso_6401_12799", "6401–12799", 12799, "(6400, 12799]"),
        Bucket("iso_ge_12800", "≥12800", None, "(12799, ∞)"),
    ),
))

FOCAL_SPEC = _validate_spec(BucketSpec(
    column="focal_35mm",
    mode="upper_inclusive",
    buckets=(
        Bucket("fl_le_16", "≤16mm", 16, "(0, 16]"),
        Bucket("fl_17_24", "17–24mm", 24, "(16, 24]"),
        Bucket("fl_25_35", "25–35mm", 35, "(24, 35]"),
        Bucket("fl_36_50", "36–50mm", 50, "(35, 50]"),
        Bucket("fl_51_85", "51–85mm", 85, "(50, 85]"),
        Bucket("fl_86_135", "86–135mm", 135, "(85, 135]"),
        Bucket("fl_136_200", "136–200mm", 200, "(135, 200]"),
        Bucket("fl_gt_200", ">200mm", None, "(200, ∞)"),
    ),
))

DURATION_SPEC = _validate_spec(BucketSpec(
    column="duration_sec",
    mode="lower_inclusive",
    buckets=(
        Bucket("d_lt_10", "<10秒", 10, "(0, 10)"),
        Bucket("d_10_30", "10–30秒", 30, "[10, 30)"),
        Bucket("d_30_60", "30秒–1分钟", 60, "[30, 60)"),
        Bucket("d_60_180", "1–3分钟", 180, "[60, 180)"),
        Bucket("d_180_600", "3–10分钟", 600, "[180, 600)"),
        Bucket("d_ge_600", "≥10分钟", None, "[600, ∞)"),
    ),
))

APERTURE_SPEC = _validate_spec(BucketSpec(
    column="f_number",
    mode="lower_inclusive",
    buckets=(
        Bucket("ap_le_1_4", "≤f/1.4", 1.6, "(0, 1.6)"),
        Bucket("ap_1_8", "f/1.8", 1.9, "[1.6, 1.9)"),
        Bucket("ap_2", "f/2", 2.4, "[1.9, 2.4)"),
        Bucket("ap_2_8", "f/2.8", 3.4, "[2.4, 3.4)"),
        Bucket("ap_4", "f/4", 4.8, "[3.4, 4.8)"),
        Bucket("ap_5_6", "f/5.6", 6.7, "[4.8, 6.7)"),
        Bucket("ap_8", "f/8", 9.5, "[6.7, 9.5)"),
        Bucket("ap_11", "f/11", 13.5, "[9.5, 13.5)"),
        Bucket("ap_16_plus", "f/16+", None, "[13.5, ∞)"),
    ),
))

SHUTTER_SPEC = _validate_spec(BucketSpec(
    column="exposure_time",
    mode="upper_inclusive",
    rel_tolerance=1e-6,
    buckets=(
        Bucket("ss_le_1_4000", "≤1/4000", 1 / 4000, "(0, 1/4000]"),
        Bucket("ss_1_4000_1_2000", "1/4000–1/2000", 1 / 2000, "(1/4000, 1/2000]"),
        Bucket("ss_1_2000_1_1000", "1/2000–1/1000", 1 / 1000, "(1/2000, 1/1000]"),
        Bucket("ss_1_1000_1_500", "1/1000–1/500", 1 / 500, "(1/1000, 1/500]"),
        Bucket("ss_1_500_1_250", "1/500–1/250", 1 / 250, "(1/500, 1/250]"),
        Bucket("ss_1_250_1_125", "1/250–1/125", 1 / 125, "(1/250, 1/125]"),
        Bucket("ss_1_125_1_60", "1/125–1/60", 1 / 60, "(1/125, 1/60]"),
        Bucket("ss_1_60_1_30", "1/60–1/30", 1 / 30, "(1/60, 1/30]"),
        Bucket("ss_1_30_1", "1/15–1s", 1, "(1/30, 1]"),
        Bucket("ss_gt_1", ">1s", None, "(1, ∞)"),
    ),
))

SCENE_EV_MIN, SCENE_EV_MAX = -6, 20   # end buckets「≤−6」「≥20」


def _ev_text(x: float) -> str:
    """-5.5 -> "−5.5", 0 -> "0", 19.5 -> "19.5"; minus sign is U+2212."""
    return f"{x:g}".replace("-", "\u2212")


def _ev_key(k: int) -> str:
    """-5 -> "ev_m5", 0 -> "ev_0", 19 -> "ev_19"."""
    return f"ev_m{-k}" if k < 0 else f"ev_{k}"


# Scene EV rounded to the nearest integer: bucket k covers [k − 0.5, k + 0.5).
SCENE_EV_SPEC = _validate_spec(BucketSpec(
    column="scene_ev",
    mode="lower_inclusive",
    signed=True,
    buckets=(
        Bucket("ev_le_m6", "≤−6", -5.5, "(−∞, −5.5)"),
        *(
            Bucket(_ev_key(k), _ev_text(k), k + 0.5,
                   f"[{_ev_text(k - 0.5)}, {_ev_text(k + 0.5)})")
            for k in range(SCENE_EV_MIN + 1, SCENE_EV_MAX)
        ),
        Bucket("ev_ge_20", "≥20", None, "[19.5, ∞)"),
    ),
))


def ev_summary(
    samples: int, median: float | None, mean: float | None
) -> dict[str, int | float | None]:
    """``{"samples", "median", "mean"}`` of ``photo.scene_ev`` (not rounded).

    The only constructor of the ``summary`` dict; ``samples == 0`` forces
    median / mean to None.
    """
    if samples == 0:
        median = mean = None
    return {"samples": samples, "median": median, "mean": mean}


_IDENT_RE = re.compile(r"^[a-z_][a-z0-9_]*$")


def bucket_case_sql(spec: BucketSpec) -> tuple[str, list[float]]:
    """Build the ``CASE`` expression assigning ``spec.column`` to a bucket key.

    NULL or <= 0 -> ``'__unknown'``; with ``spec.signed`` only NULL is unknown
    (0 and negatives are valid values). Thresholds are bound parameters (value
    ``upper * (1 + rel_tolerance)``); keys and the column name are code constants.
    """
    if not _IDENT_RE.match(spec.column):
        raise ValueError(f"invalid column name {spec.column!r}")
    col = spec.column
    op = "<=" if spec.mode == "upper_inclusive" else "<"
    unknown_cond = f"{col} IS NULL" if spec.signed else f"{col} IS NULL OR {col} <= 0"
    parts = [f"CASE WHEN {unknown_cond} THEN '{UNKNOWN_KEY}'"]
    params: list[float] = []
    for bucket in spec.buckets[:-1]:
        parts.append(f"WHEN {col} {op} ? THEN '{bucket.key}'")
        params.append(float(bucket.upper) * (1 + spec.rel_tolerance))
    parts.append(f"ELSE '{spec.buckets[-1].key}' END")
    return " ".join(parts), params


# ---------------------------------------------------------------------------
# Weekday / hour enumerations
# ---------------------------------------------------------------------------

# strftime('%w') -> 0 = Sunday ... 6 = Saturday. Output order is Monday first.
WEEKDAY_BUCKETS: tuple[tuple[int, str, str], ...] = (
    (1, "mon", "周一"),
    (2, "tue", "周二"),
    (3, "wed", "周三"),
    (4, "thu", "周四"),
    (5, "fri", "周五"),
    (6, "sat", "周六"),
    (0, "sun", "周日"),
)

HOUR_BUCKETS: tuple[tuple[int, str, str], ...] = tuple(
    (h, f"{h:02d}", f"{h}时") for h in range(24)
)


# ---------------------------------------------------------------------------
# Formats (§5.4)
# ---------------------------------------------------------------------------

RAW_EXTS: tuple[str, ...] = ("dng", "arw", "cr2", "cr3", "nef", "orf", "raf", "rw2")

IMAGE_EXTS: tuple[str, ...] = (
    "jpg", "jpeg", "jpe", "jfif", "heic", "heif", "hif", "png", "webp", "gif",
    "bmp", "tif", "tiff", "avif",
) + RAW_EXTS

# (group key, label, explicit extensions). Order is the output order.
FORMAT_GROUPS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("jpeg", "JPEG", ("jpg", "jpeg", "jpe", "jfif")),
    ("heic", "HEIC/HEIF", ("heic", "heif", "hif")),
    ("png", "PNG", ("png",)),
    ("webp", "WebP", ("webp",)),
    ("gif", "GIF", ("gif",)),
    ("raw", "RAW", RAW_EXTS),
    ("mp4", "MP4", ("mp4",)),
    ("mov", "MOV", ("mov",)),
    ("video_other", "其他视频", ()),
    ("no_ext", "无扩展名", ()),
    ("misc", "其他格式", ()),
)

FORMAT_GROUP_LABELS: dict[str, str] = {key: label for key, label, _ in FORMAT_GROUPS}
FORMAT_GROUPS_WITH_CHILDREN = frozenset({"raw", "video_other", "misc"})
FORMAT_CHILDREN_MAX = 20
FORMAT_REST_KEY = "_rest"
FORMAT_REST_LABEL = "其余"

_EXT_TO_GROUP: dict[str, str] = {
    ext: key for key, _, exts in FORMAT_GROUPS for ext in exts
}


def format_group(ext: str, cat: str) -> str:
    """Map an extension (lower case, no dot) to its format group (§5.4 order)."""
    if ext == "":
        return "no_ext"
    group = _EXT_TO_GROUP.get(ext)
    if group is not None:
        return group
    if cat == "video":
        return "video_other"
    return "misc"


CATEGORY_BUCKETS: tuple[tuple[str, str], ...] = (
    ("photo", "照片"),
    ("video", "视频"),
    ("other", "其他"),
)


# ---------------------------------------------------------------------------
# Shares (§5.6)
# ---------------------------------------------------------------------------


def allocate_shares(counts: Sequence[int], total: int) -> list[float]:
    """Percentages with one decimal that always add up to exactly 100.0%.

    Works in integer tenths of a percent (1000 units in total) using the
    largest-remainder method; ties go to the lower index. ``total == 0`` gives
    all zeros. The caller guarantees ``sum(counts) == total``.
    """
    assert sum(counts) == total, f"sum(counts)={sum(counts)} != total={total}"
    if total == 0:
        return [0.0] * len(counts)
    base = [c * 1000 // total for c in counts]
    rems = [c * 1000 % total for c in counts]
    left = 1000 - sum(base)
    order = sorted(range(len(counts)), key=lambda i: (-rems[i], i))
    for i in order[:left]:
        base[i] += 1
    return [t / 10 for t in base]


def pct(value: int, total: int) -> float:
    """Independent one-decimal percentage (format children; no sum guarantee)."""
    if total == 0:
        return 0.0
    return round(value * 100 / total, 1)


def bucket_distribution(
    spec_buckets: Iterable[tuple[str, str, str | None]],
    counts: dict[str, int],
    unknown: int,
    meta: dict[str, int] | None = None,
) -> dict[str, Any]:
    """Build a ``kind='distribution'`` dict from fixed buckets + per-key counts.

    ``spec_buckets`` yields ``(key, label, range_or_None)`` in output order;
    missing keys count as 0. ``total = Σbuckets + unknown``.
    """
    items = list(spec_buckets)
    values = [int(counts.get(key, 0)) for key, _, _ in items]
    total = sum(values) + unknown
    shares = allocate_shares([*values, unknown], total)
    buckets = []
    for (key, label, rng), count, share in zip(items, values, shares):
        bucket: dict[str, Any] = {"key": key, "label": label, "count": count, "share": share}
        if rng is not None:
            bucket["range"] = rng
        buckets.append(bucket)
    result: dict[str, Any] = {
        "kind": "distribution",
        "buckets": buckets,
        "unknown": unknown,
        "unknown_share": shares[-1],
        "total": total,
    }
    if meta is not None:
        result["meta"] = meta
    return result


def spec_items(spec: BucketSpec) -> list[tuple[str, str, str | None]]:
    return [(b.key, b.label, b.range) for b in spec.buckets]


# ---------------------------------------------------------------------------
# Rankings (§5.4)
# ---------------------------------------------------------------------------


def camera_display_name(make: object, model: object) -> str | None:
    """Merge EXIF Make / Model into one display name (None = unknown)."""
    make_n = normalize_text(make)
    model_n = normalize_text(model)
    if not model_n:
        return None
    if not make_n:
        return model_n
    model_cf = model_n.casefold()

    def _covers(prefix: str) -> bool:
        p = prefix.casefold()
        return model_cf == p or model_cf.startswith(p + " ")

    if _covers(make_n):
        return model_n
    first_word = make_n.split()[0]
    if _covers(first_word):
        return model_n
    return f"{make_n} {model_n}"


def lens_display_name(lens: object) -> str | None:
    return normalize_text(lens)


def build_ranking(
    pairs: Iterable[tuple[str | None, int]], total: int, top_n: int
) -> dict[str, Any]:
    """Top-N ranking with「其他」(``other``) and「未知」(``unknown``).

    ``pairs`` are ``(display name or None, count)``; names merge by
    ``casefold()``. The label of a merged entry is the original spelling with
    the highest count (ties: lexicographically smallest). ``total`` is the
    category's file count, so rows without any value also land in ``unknown``.
    """
    merged: dict[str, dict[str, int]] = {}
    for name, count in pairs:
        if name is None or count <= 0:
            continue
        spellings = merged.setdefault(name.casefold(), {})
        spellings[name] = spellings.get(name, 0) + int(count)

    entries = []
    for key, spellings in merged.items():
        label = min(spellings.items(), key=lambda kv: (-kv[1], kv[0]))[0]
        entries.append((key, label, sum(spellings.values())))
    entries.sort(key=lambda e: (-e[2], e[0]))

    top = entries[:top_n]
    other = sum(e[2] for e in entries[top_n:])
    unknown = total - sum(e[2] for e in top) - other
    assert unknown >= 0, "ranking counts exceed the category total"

    shares = allocate_shares([*(e[2] for e in top), other, unknown], total)
    return {
        "kind": "ranking",
        "buckets": [
            {"key": key, "label": label, "count": count, "share": share}
            for (key, label, count), share in zip(top, shares)
        ],
        "other": other,
        "other_share": shares[-2],
        "unknown": unknown,
        "unknown_share": shares[-1],
        "total": total,
    }


# ---------------------------------------------------------------------------
# Trends (§5.4)
# ---------------------------------------------------------------------------


def _series(points: list[tuple[str, str, int]], total: int) -> dict[str, Any]:
    known = sum(c for _, _, c in points)
    unknown = total - known
    assert unknown >= 0, "trend counts exceed the category total"
    shares = allocate_shares([*(c for _, _, c in points), unknown], total)
    return {
        "kind": "series",
        "buckets": [
            {"key": k, "label": label, "count": c, "share": s}
            for (k, label, c), s in zip(points, shares)
        ],
        "unknown": unknown,
        "unknown_share": shares[-1],
        "total": total,
    }


def build_trends(
    rows: Iterable[tuple[str, str, int]], totals: dict[str, int]
) -> dict[str, dict[str, dict[str, Any]]]:
    """Monthly / yearly series for photos and videos on one shared axis.

    ``rows`` are ``(cat, 'YYYY-MM', count)``; ``totals`` maps ``'photo'`` /
    ``'video'`` to the category file count. Returns
    ``{'photo': {'monthly': …, 'yearly': …}, 'video': {…}}``. The axis runs from
    the earliest to the latest valid month across both categories, padded with
    zeros; years are summed from months.
    """
    monthly: dict[str, dict[tuple[int, int], int]] = {"photo": {}, "video": {}}
    for cat, ym, count in rows:
        if cat not in monthly or not ym:
            continue
        y, m = int(ym[0:4]), int(ym[5:7])
        monthly[cat][(y, m)] = monthly[cat].get((y, m), 0) + int(count)

    all_months = [k for per_cat in monthly.values() for k in per_cat]
    result: dict[str, dict[str, dict[str, Any]]] = {}
    if not all_months:
        for cat in ("photo", "video"):
            total = int(totals.get(cat, 0))
            result[cat] = {"monthly": _series([], total), "yearly": _series([], total)}
        return result

    start, end = min(all_months), max(all_months)
    axis: list[tuple[int, int]] = []
    y, m = start
    while (y, m) <= end:
        axis.append((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    years = list(range(start[0], end[0] + 1))

    for cat in ("photo", "video"):
        per_month = monthly[cat]
        per_year: dict[int, int] = {}
        for (yy, _), c in per_month.items():
            per_year[yy] = per_year.get(yy, 0) + c
        total = int(totals.get(cat, 0))
        result[cat] = {
            "monthly": _series(
                [(f"{yy:04d}-{mm:02d}", f"{yy}年{mm}月", per_month.get((yy, mm), 0))
                 for yy, mm in axis],
                total,
            ),
            "yearly": _series(
                [(f"{yy:04d}", f"{yy}年", per_year.get(yy, 0)) for yy in years],
                total,
            ),
        }
    return result


def build_daily(rows: Iterable[tuple[str, int]], total: int) -> dict[str, Any]:
    """Per-day photo + video counts by local capture date (heatmap).

    ``rows`` are ``('YYYY-MM-DD', count)``; ``total`` is the photo + video file
    count. Duplicate keys are merged, falsy keys skipped, days with a zero
    count dropped. Returns ``{'kind': 'calendar', 'days': {...ascending...},
    'unknown': total - Σ days, 'total': total}``; ``today`` is added by the API.
    """
    days: dict[str, int] = {}
    for ymd, count in rows:
        if not ymd:
            continue
        days[ymd] = days.get(ymd, 0) + int(count)
    ordered = {k: days[k] for k in sorted(days) if days[k] > 0}
    unknown = total - sum(ordered.values())
    assert unknown >= 0, "daily counts exceed the photo + video total"
    return {"kind": "calendar", "days": ordered, "unknown": unknown, "total": total}


# ---------------------------------------------------------------------------
# Default time zone and UTC -> local interval table (§4.4, §5.3)
# ---------------------------------------------------------------------------

_UTC = timezone.utc
_TZ_NAME_RE = re.compile(r"^[A-Za-z0-9_+\-]+(/[A-Za-z0-9_+\-]+)*$")
_INTERVAL_START = datetime(1900, 1, 1, tzinfo=_UTC)
_FIRST_ROW_START = "0000-01-01T00:00:00"

# Invalid ``default_timezone`` values already reported (WARNING once per process).
_warned_config_tz: set[str] = set()


@dataclass(frozen=True)
class ResolvedTz:
    tzinfo: tzinfo
    name: str                                   # 'Asia/Shanghai' or 'UTC+08:00'
    source: Literal["config", "request", "server"]
    cache_key: str                              # 'iana:…' / 'env:…' / 'fixed:+28800'


def _load_zone(name: str) -> ZoneInfo | None:
    """``ZoneInfo(name)`` or None when the name is not a valid IANA key."""
    if not name or len(name) > 64 or not _TZ_NAME_RE.match(name):
        return None
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError, OSError):
        return None


def is_valid_tz_name(name: str) -> bool:
    return _load_zone(name) is not None


def _format_fixed_offset(seconds: int) -> str:
    sign = "+" if seconds >= 0 else "-"
    s = abs(seconds)
    hh, rem = divmod(s, 3600)
    mm, ss = divmod(rem, 60)
    text = f"UTC{sign}{hh:02d}:{mm:02d}"
    if ss:
        text += f":{ss:02d}"
    return text


def _reset_tz_warnings() -> None:
    """Forget which invalid ``default_timezone`` values were reported (tests)."""
    _warned_config_tz.clear()


def resolve_default_tz(request_tz: str | None, settings: "Settings") -> ResolvedTz:
    """Pick the default time zone: config > request > ``TZ`` env > local offset."""
    configured = (settings.default_timezone or "").strip()
    if configured:
        zone = _load_zone(configured)
        if zone is not None:
            return ResolvedTz(zone, configured, "config", f"iana:{configured}")
        if configured not in _warned_config_tz:
            _warned_config_tz.add(configured)
            logger.warning(
                "Invalid default_timezone %r ignored; falling back to the request "
                "or server time zone",
                configured,
            )

    if request_tz:
        zone = _load_zone(request_tz)
        if zone is not None:
            return ResolvedTz(zone, request_tz, "request", f"iana:{request_tz}")

    env_tz = (os.environ.get("TZ") or "").strip().lstrip(":")
    if env_tz:
        zone = _load_zone(env_tz)
        if zone is not None:
            return ResolvedTz(zone, env_tz, "server", f"env:{env_tz}")

    offset = datetime.now().astimezone().utcoffset() or timedelta(0)
    seconds = int(offset.total_seconds())
    return ResolvedTz(
        timezone(timedelta(seconds=seconds)),
        _format_fixed_offset(seconds),
        "server",
        f"fixed:{seconds:+d}",
    )


def _offset_at(tz: tzinfo, utc_instant: datetime) -> int:
    """Offset in seconds of ``tz`` at the UTC instant ``utc_instant`` (tz-aware)."""
    return int(utc_instant.astimezone(tz).utcoffset().total_seconds())


def _tz_from_cache_key(cache_key: str) -> tzinfo:
    kind, _, value = cache_key.partition(":")
    if kind in ("iana", "env"):
        return ZoneInfo(value)
    if kind == "fixed":
        return timezone(timedelta(seconds=int(value)))
    raise ValueError(f"unknown tz cache key {cache_key!r}")


def _modifier(seconds: int) -> str:
    return f"{seconds:+d} seconds"


@functools.lru_cache(maxsize=32)
def _intervals_cached(cache_key: str, last_year: int) -> tuple[tuple[str, str], ...]:
    tz = _tz_from_cache_key(cache_key)
    prev = _offset_at(tz, _INTERVAL_START)
    rows: list[tuple[str, str]] = [(_FIRST_ROW_START, _modifier(prev))]
    if isinstance(tz, timezone):
        return tuple(rows)

    one_day = timedelta(days=1)
    end = datetime(last_year + 1, 1, 1, tzinfo=_UTC)
    day = _INTERVAL_START
    while day < end:
        nxt = day + one_day
        off = _offset_at(tz, nxt)
        if off != prev:
            # Bisect by minute for the first instant in (day, nxt] with a new offset.
            lo, hi = 0, 24 * 60
            while hi - lo > 1:
                mid = (lo + hi) // 2
                if _offset_at(tz, day + timedelta(minutes=mid)) == prev:
                    lo = mid
                else:
                    hi = mid
            instant = day + timedelta(minutes=hi)
            new_off = _offset_at(tz, instant)
            rows.append((instant.strftime("%Y-%m-%dT%H:%M:%S"), _modifier(new_off)))
            prev = new_off
            if new_off != off:
                # Two changes within one day: record the end-of-day offset too.
                rows.append((nxt.strftime("%Y-%m-%dT%H:%M:%S"), _modifier(off)))
                prev = off
        day = nxt
    return tuple(rows)


def utc_offset_intervals(rtz: ResolvedTz, last_year: int) -> tuple[tuple[str, str], ...]:
    """``(utc_start, '<±N> seconds')`` rows covering year 0 to ``last_year``.

    A UTC instant ``t`` converts to local time with the modifier of the last row
    whose ``utc_start <= t``. Synchronous and CPU-bound on a cache miss; the API
    layer calls it via ``asyncio.to_thread``.
    """
    return _intervals_cached(rtz.cache_key, last_year)
