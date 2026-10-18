"""Web timeline queries: browser-local months, filters, paging, aggregates.

Backs ``GET /api/v1/files/timeline/{facets,summary,files}``. The Web timeline
used to download the whole library through ``/files/all`` and filter/group in
the browser; these queries do the same work in SQLite with identical semantics:

- Time key ``K = COALESCE(exif_time, created_at)`` (sort key of ``/files/all``),
  ordered ``K DESC, id DESC``.
- Rows are grouped by the *browser's* local calendar month: values the browser
  treats as instants (SQLite ``created_at`` UTC format, pure dates, ``Z`` /
  ``±HH:MM`` suffixes) are converted with the request time zone through the
  connection-local interval table ``temp.timeline_tz`` (same scheme as
  ``MediaStatsService``); other values (Android ``exif_time`` wall-clock time)
  are taken as-is. Local times outside ``[1000-01-01, 9998-12-31]`` count as
  "no date" and are never listed or counted.
- File-name search and the format filter use the stored derived columns
  ``file_name_key`` / ``file_ext`` and fall back to the Python functions of
  ``app.services.filename_search`` (registered as SQL functions) for rows the
  backfill has not reached yet, so results never depend on backfill progress.

Invariants owned here: every query starts with ``_ACTIVE_SQL`` (current user,
not trashed, not purged — the same filter as ``list_all_files``); only ``?``
positional placeholders are used and each query asserts that the placeholder
count equals the parameter count; user input never enters SQL text.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date, timedelta
from typing import TYPE_CHECKING, Callable, Optional, Sequence
from zoneinfo import ZoneInfo

import aiosqlite

from app.services.file_browse_service import FileBrowseInfo
from app.services.filename_search import (
    SearchKeyword,
    file_ext,
    file_name_key,
    normalize_for_search,
)
from app.services.stats_buckets import ResolvedTz, is_valid_tz_name, resolve_default_tz

if TYPE_CHECKING:
    from app.core.config import Settings

logger = logging.getLogger("photovault.timeline")

# ---------------------------------------------------------------------------
# SQL templates. None of them contains a placeholder; ``_sql`` expands them.
# ---------------------------------------------------------------------------

# The row's time key; must match the idx_file_records_timeline expression.
_K = "COALESCE(fr.exif_time, fr.created_at)"

# Formats the browser parses as absolute instants (normalizeTimestamp + Date).
_INSTANT_SQL = """(   {K} GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]'
 OR ({K} GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9]*'
     AND substr({K}, 21) NOT GLOB '*[^0-9]*')
 OR {K} GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
 OR instr({K}, 'Z') > 0 OR instr({K}, 'z') > 0
 OR {K} GLOB '*[+-][0-9][0-9]:[0-9][0-9]' OR {K} GLOB '*[+-][0-9][0-9][0-9][0-9]' )"""

# Browser-local time 'YYYY-MM-DD HH:MM:SS' of the row, or NULL.
_LOCAL_TIME_SQL = """(CASE WHEN datetime({K}) IS NULL THEN NULL
     WHEN {INSTANT} THEN datetime(datetime({K}),
          (SELECT z.modifier FROM temp.timeline_tz AS z
            WHERE z.utc_start <= datetime({K}) ORDER BY z.utc_start DESC LIMIT 1))
     ELSE datetime({K}) END)"""

# Stored derived columns; Python fallback only for NULL (not yet backfilled).
_NAME_KEY_SQL = (
    "(CASE WHEN fr.file_name_key IS NOT NULL THEN fr.file_name_key "
    "ELSE pv_name_key(fr.file_name) END)"
)
_FILE_EXT_SQL = (
    "(CASE WHEN fr.file_ext IS NOT NULL THEN fr.file_ext "
    "ELSE pv_file_ext(fr.file_name) END)"
)

# Same buckets as the Web timeline's focalBucketKey.
_FOCAL_BUCKET_SQL = (
    "(CASE WHEN fr.focal_length IS NULL OR fr.focal_length <= 0 THEN 'unknown' "
    "WHEN fr.focal_length <= 20 THEN 'ultrawide' "
    "WHEN fr.focal_length <= 35 THEN 'wide' "
    "WHEN fr.focal_length <= 70 THEN 'standard' "
    "WHEN fr.focal_length <= 135 THEN 'tele' "
    "ELSE 'supertele' END)"
)

# Identical to the list_all_files filter (user isolation + trash exclusion).
_ACTIVE_SQL = "fr.user_id = ? AND fr.deleted_at IS NULL AND fr.purged_at IS NULL"

# Makes the files query imply the third term of the partial index condition.
_INDEXED_K_SQL = "{K} IS NOT NULL"

FOCAL_BUCKETS = ("ultrawide", "wide", "standard", "tele", "supertele", "unknown")

_MIN_LOCAL_DATE = "1000-01-01"
_MAX_LOCAL_DATE = "9998-12-31"
_MIN_MONTH = "1000-01"
_MAX_MONTH = "9998-12"

_SELECT_FILE_COLUMNS = (
    "fr.id, fr.file_name, fr.file_size, fr.mime_type, fr.exif_time, fr.media_type, "
    "fr.is_motion_photo, fr.is_ultra_hdr, fr.created_at, fr.device_name, fr.focal_length"
)


def _sql(template: str) -> str:
    """Expand the ``{LOCAL}``/``{INSTANT}``/``{NAME_KEY}``/... fragments, then ``{K}``."""
    return (
        template.replace("{LOCAL}", _LOCAL_TIME_SQL)
        .replace("{INSTANT}", _INSTANT_SQL)
        .replace("{NAME_KEY}", _NAME_KEY_SQL)
        .replace("{FILE_EXT}", _FILE_EXT_SQL)
        .replace("{FOCAL_BUCKET}", _FOCAL_BUCKET_SQL)
        .replace("{K}", _K)
    )


def _placeholders(n: int) -> str:
    return ", ".join("?" * n)


# ---------------------------------------------------------------------------
# Value objects
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class TimelineFilters:
    """Timeline filters; each one AND-ed with the others."""

    keywords: tuple[SearchKeyword, ...] = ()
    devices: tuple[str, ...] = ()
    formats: tuple[str, ...] = ()
    focals: tuple[str, ...] = ()
    date_from: Optional[str] = None  # 'YYYY-MM-DD', browser-local, inclusive
    date_to: Optional[str] = None

    @property
    def active(self) -> bool:
        return bool(
            self.keywords
            or self.devices
            or self.formats
            or self.focals
            or (self.date_from is not None and self.date_to is not None)
        )

    def sql(self) -> tuple[str, list]:
        """``(filter_sql, filter_params)``; ``("1", [])`` without filters."""
        parts: list[str] = []
        params: list = []
        for kw in self.keywords:
            if kw.raw:
                parts.append("instr(pv_name_norm(fr.file_name), ?) > 0")
            else:
                parts.append(_sql("instr({NAME_KEY}, ?) > 0"))
            params.append(kw.value)
        if self.devices:
            parts.append(f"fr.device_name IN ({_placeholders(len(self.devices))})")
            params.extend(self.devices)
        if self.formats:
            parts.append(_sql(f"{{FILE_EXT}} IN ({_placeholders(len(self.formats))})"))
            params.extend(self.formats)
        if self.focals:
            parts.append(_sql(f"{{FOCAL_BUCKET}} IN ({_placeholders(len(self.focals))})"))
            params.extend(self.focals)
        if self.date_from is not None and self.date_to is not None:
            parts.append(_sql("substr({LOCAL}, 1, 10) BETWEEN ? AND ?"))
            params.extend([self.date_from, self.date_to])
        if not parts:
            return "1", []
        return " AND ".join(parts), params


@dataclass(frozen=True)
class TimelineCursor:
    """Keyset cursor: ``COALESCE(exif_time, created_at)`` and ``id`` of the last row."""

    time: str
    id: int


@dataclass(frozen=True)
class TimelineMonthCount:
    year: int
    month: int
    count: int


@dataclass(frozen=True)
class TimelineSummary:
    total_count: int
    filtered_count: int
    months: list[TimelineMonthCount]  # newest first, count > 0 only


@dataclass(frozen=True)
class TimelineFacets:
    devices: list[str]
    formats: list[str]
    focal_buckets: list[str]  # FOCAL_BUCKETS order
    photo_dates: list[str]    # ascending 'YYYY-MM-DD'


def month_bounds(year: int, month: int) -> tuple[str, str, str, str]:
    """``(month_start, next_month_start, env_lo, env_hi)`` of a local month.

    ``month_start`` / ``next_month_start`` bound the local time
    (``'YYYY-MM-01 00:00:00'``). ``env_lo`` / ``env_hi`` are ``'YYYY-MM-DD'``
    prefixes 2 days outside the month: an index range on the raw time key that
    contains every row of the month (UTC rows differ from local time by at most
    14 h, offset-suffixed rows by at most 28 h).
    """
    first = date(year, month, 1)
    nxt = date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)
    two_days = timedelta(days=2)
    return (
        f"{first.isoformat()} 00:00:00",
        f"{nxt.isoformat()} 00:00:00",
        (first - two_days).isoformat(),
        (nxt + two_days).isoformat(),
    )


def resolve_timeline_tz(request_tz: Optional[str], settings: "Settings") -> ResolvedTz:
    """The browser's time zone when valid, else the server default.

    Request first, unlike ``/stats/media``: the timeline must group exactly
    like the browser displays.
    """
    if request_tz and is_valid_tz_name(request_tz):
        return ResolvedTz(ZoneInfo(request_tz), request_tz, "request", f"iana:{request_tz}")
    return resolve_default_tz(None, settings)


def _null_safe(fn: Callable[[str], str]) -> Callable[[object], Optional[str]]:
    def wrapper(value: object) -> Optional[str]:
        return None if value is None else fn(str(value))

    return wrapper


_pv_name_key = _null_safe(file_name_key)
_pv_name_norm = _null_safe(normalize_for_search)
_pv_file_ext = _null_safe(file_ext)


def _row_to_file(r: Sequence) -> FileBrowseInfo:
    """Same mapping as ``FileBrowseService.list_all_files`` (column order of
    ``_SELECT_FILE_COLUMNS``)."""
    return FileBrowseInfo(
        id=r[0],
        file_name=r[1],
        file_size=r[2],
        mime_type=r[3],
        exif_time=r[4],
        media_type=r[5] or "image",
        is_motion_photo=bool(r[6]),
        is_ultra_hdr=bool(r[7]),
        created_at=r[8],
        device_name=r[9],
        focal_length=r[10],
    )


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------


class TimelineService:
    """Timeline queries on one request connection.

    Each public method prepares the connection (temp time-zone table, SQL
    functions) and drops the temp table afterwards. Raises ``aiosqlite.Error``
    on database failures; the API layer turns them into 500.
    """

    def __init__(self, db: aiosqlite.Connection):
        self._db = db

    async def _prepare(self, intervals: Sequence[tuple[str, str]]) -> None:
        db = self._db
        await db.execute("PRAGMA temp_store = MEMORY")
        await db.create_function("pv_name_key", 1, _pv_name_key, deterministic=True)
        await db.create_function("pv_name_norm", 1, _pv_name_norm, deterministic=True)
        await db.create_function("pv_file_ext", 1, _pv_file_ext, deterministic=True)
        await db.execute("DROP TABLE IF EXISTS temp.timeline_tz")
        await db.execute(
            "CREATE TEMP TABLE timeline_tz ("
            "utc_start TEXT PRIMARY KEY, modifier TEXT NOT NULL) WITHOUT ROWID"
        )
        # Space-separated so utc_start compares directly with datetime() output.
        await db.executemany(
            "INSERT INTO temp.timeline_tz (utc_start, modifier) VALUES (?, ?)",
            [(start.replace("T", " "), modifier) for start, modifier in intervals],
        )
        # End the implicit transaction so no read snapshot is held afterwards.
        await db.commit()

    async def _cleanup(self) -> None:
        try:
            await self._db.execute("DROP TABLE IF EXISTS temp.timeline_tz")
            await self._db.commit()
        except Exception:  # noqa: BLE001 - the connection is closed right after
            logger.debug("Dropping temp.timeline_tz failed", exc_info=True)

    async def _fetchall(self, sql: str, params: list) -> list:
        cursor = await self._db.execute(sql, params)
        return list(await cursor.fetchall())

    async def facets(
        self, user_id: int, intervals: Sequence[tuple[str, str]]
    ) -> TimelineFacets:
        """Library-wide filter options and photo dates (independent of filters)."""
        await self._prepare(intervals)
        try:
            sql = _sql(
                f"""SELECT fr.device_name, {{FILE_EXT}}, {{FOCAL_BUCKET}}, COUNT(*)
                      FROM file_records AS fr INDEXED BY idx_file_records_trash
                     WHERE {_ACTIVE_SQL}
                     GROUP BY 1, 2, 3"""
            )
            params = [user_id]
            assert sql.count("?") == len(params)
            devices: set[str] = set()
            formats: set[str] = set()
            focals: set[str] = set()
            for device, ext, bucket, _count in await self._fetchall(sql, params):
                if device:
                    devices.add(device)
                if ext:
                    formats.add(ext)
                focals.add(bucket)

            sql = _sql(
                f"""SELECT DISTINCT substr({{LOCAL}}, 1, 10)
                      FROM file_records AS fr INDEXED BY idx_file_records_trash
                     WHERE {_ACTIVE_SQL}"""
            )
            params = [user_id]
            assert sql.count("?") == len(params)
            photo_dates = sorted(
                d
                for (d,) in await self._fetchall(sql, params)
                if d is not None and _MIN_LOCAL_DATE <= d <= _MAX_LOCAL_DATE
            )
        finally:
            await self._cleanup()
        return TimelineFacets(
            devices=sorted(devices),
            formats=sorted(formats),
            focal_buckets=[b for b in FOCAL_BUCKETS if b in focals],
            photo_dates=photo_dates,
        )

    async def summary(
        self,
        user_id: int,
        filters: TimelineFilters,
        intervals: Sequence[tuple[str, str]],
    ) -> TimelineSummary:
        """Library total plus filtered per-month counts, in one scan."""
        await self._prepare(intervals)
        try:
            filter_sql, filter_params = filters.sql()
            sql = _sql(
                f"""SELECT substr({{LOCAL}}, 1, 7) AS ym,
                           COUNT(*) AS total,
                           SUM(CASE WHEN {filter_sql} THEN 1 ELSE 0 END) AS matched
                      FROM file_records AS fr INDEXED BY idx_file_records_trash
                     WHERE {_ACTIVE_SQL}
                     GROUP BY ym"""
            )
            # The filter placeholders in the SELECT list precede the WHERE ones.
            params = [*filter_params, user_id]
            assert sql.count("?") == len(params)
            rows = await self._fetchall(sql, params)
        finally:
            await self._cleanup()

        total = 0
        months: list[TimelineMonthCount] = []
        for ym, count, matched in rows:
            if ym is None or not (_MIN_MONTH <= ym <= _MAX_MONTH):
                continue  # no displayable date
            total += int(count)
            if matched:
                months.append(TimelineMonthCount(int(ym[:4]), int(ym[5:7]), int(matched)))
        months.sort(key=lambda m: (m.year, m.month), reverse=True)
        return TimelineSummary(
            total_count=total,
            filtered_count=sum(m.count for m in months),
            months=months,
        )

    @staticmethod
    def _list_month_sql(
        user_id: int,
        filters: TimelineFilters,
        year: int,
        month: int,
        offset: int,
        after: Optional[TimelineCursor],
        limit: int,
    ) -> tuple[str, list]:
        """SQL and parameters of one ``files`` page (pure; no database access)."""
        month_start, next_month_start, env_lo, env_hi = month_bounds(year, month)
        filter_sql, filter_params = filters.sql()
        if after is None:
            # First page or in-month offset: the envelope is the upper bound.
            upper_sql, upper_params = "AND {K} < ?", [env_hi]
        else:
            # The cursor comes from a row of this month, so it is already
            # < env_hi and dropping the envelope bound changes no result.
            # Only one upper bound on the index column lets SQLite use it as
            # the end of the index range; with both, it picks the envelope and
            # every page scans from the month's first row to the cursor.
            upper_sql = "AND {K} <= ? AND ({K} < ? OR ({K} = ? AND fr.id < ?))"
            upper_params = [after.time, after.time, after.time, after.id]
        sql = _sql(
            f"""SELECT {_SELECT_FILE_COLUMNS}
                  FROM file_records AS fr INDEXED BY idx_file_records_timeline
                 WHERE {_ACTIVE_SQL}
                   AND {_INDEXED_K_SQL}
                   AND {{K}} >= ?
                   {upper_sql}
                   AND {{LOCAL}} >= ? AND {{LOCAL}} < ?
                   AND {filter_sql}
                 ORDER BY {{K}} DESC, fr.id DESC
                 LIMIT ? OFFSET ?"""
        )
        params = [
            user_id,
            env_lo,
            *upper_params,
            month_start,
            next_month_start,
            *filter_params,
            limit + 1,
            offset,
        ]
        assert sql.count("?") == len(params)
        return sql, params

    async def list_month(
        self,
        user_id: int,
        filters: TimelineFilters,
        intervals: Sequence[tuple[str, str]],
        year: int,
        month: int,
        offset: int = 0,
        after: Optional[TimelineCursor] = None,
        limit: int = 200,
    ) -> tuple[list[FileBrowseInfo], bool]:
        """One page of a local month, ``K DESC, id DESC``; returns ``(files, has_more)``."""
        sql, params = self._list_month_sql(user_id, filters, year, month, offset, after, limit)
        await self._prepare(intervals)
        try:
            rows = await self._fetchall(sql, params)
        finally:
            await self._cleanup()
        has_more = len(rows) > limit
        return [_row_to_file(r) for r in rows[:limit]], has_more
