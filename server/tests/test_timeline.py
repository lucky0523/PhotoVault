"""Tests for the Web timeline queries and endpoints (design V1.2-V1.10).

``TimelineService`` is exercised directly on temp SQLite databases built by
``init_db``; the endpoints through ``httpx.ASGITransport`` with the same env
fixture as ``test_api_stats.py``. Results are compared with reference
implementations written here in Python with the old Web front end's semantics
(``normalizeTimestamp`` + ``new Date()``, ``focalBucketKey``, ``fileExt``,
``filenameSearch.ts``).
"""

from __future__ import annotations

import dataclasses
import itertools
import logging
import os
import re
import sqlite3
from datetime import datetime, timezone
from typing import Optional
from zoneinfo import ZoneInfo

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.files import router as files_router
from app.core import database
from app.core.config import get_settings, reset_settings
from app.core.database import get_db, init_db
from app.services.auth_service import AuthService
from app.services.deduplication_service import DeduplicationService
from app.services.file_browse_service import FileBrowseService
from app.services.filename_key_backfill import run_filename_key_backfill_round
from app.services.filename_search import (
    derived_name_columns,
    file_ext,
    file_name_key,
    normalize_for_search,
    parse_search_query,
)
from app.services.stats_buckets import ResolvedTz, utc_offset_intervals
from app.services.timeline_service import (
    TimelineCursor,
    TimelineFilters,
    TimelineService,
    _K,
    _sql,
    month_bounds,
    resolve_timeline_tz,
)

# Mixing ? and :name placeholders warns on 3.12+ (error on 3.14): fail loudly.
pytestmark = pytest.mark.filterwarnings("error::DeprecationWarning")

TZS = ("Asia/Shanghai", "America/New_York", "UTC")
LONG_DEVICE = "D" * 2000
LONG_DOTTED_NAME = "Mr." + "x" * 300  # file_ext -> 300 upper-case characters
LONG_EXT = "X" * 300


# ---------------------------------------------------------------------------
# Fixtures and helpers
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_settings_singleton():
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def db_path(tmp_path):
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-timeline-tests-0123456789"
    saved = {
        key: os.environ.pop(key, None)
        for key in ("PHOTOVAULT_DEFAULT_TIMEZONE", "PHOTOVAULT_MAX_USERS")
    }
    reset_settings()

    path = str(tmp_path / "test.db")
    await init_db(path)

    yield path

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    for key, value in saved.items():
        os.environ.pop(key, None)
        if value is not None:
            os.environ[key] = value
    reset_settings()


def _make_app(db_path: str) -> FastAPI:
    app = FastAPI()

    async def _override_get_db():
        db = await aiosqlite.connect(db_path)
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        try:
            yield db
        finally:
            await db.close()

    app.dependency_overrides[get_db] = _override_get_db
    app.include_router(files_router)
    return app


@pytest.fixture
async def client(db_path):
    transport = ASGITransport(app=_make_app(db_path))
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


async def _create_user(db_path: str, username: str) -> tuple[int, str]:
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user(username, "password123")
        tokens = await auth.login(username, "password123")
    return user.id, tokens.access_token


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


_row_seq = itertools.count(1)
_UNSET = object()


async def _insert_rows(db_path: str, rows: list[dict], derived: bool = False) -> list[int]:
    """Insert raw file_records rows; derived columns stay NULL unless ``derived``."""
    ids = []
    async with aiosqlite.connect(db_path) as db:
        for r in rows:
            n = next(_row_seq)
            name = r.get("file_name", f"IMG_{n:05d}.JPG")
            key, ext = derived_name_columns(name) if derived else (None, None)
            created = r.get("created_at", _UNSET)
            cursor = await db.execute(
                """INSERT INTO file_records
                   (user_id, file_hash, file_path, original_path, device_name,
                    file_size, file_name, exif_time, created_at, focal_length,
                    deleted_at, purged_at, media_type, file_name_key, file_ext,
                    mime_type, capture_time, capture_time_kind, is_reference)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    r.get("user_id", 1),
                    r.get("file_hash", f"hash{n}"),
                    r.get("file_path", f"/m/u/Dev/{n}/{name}"),
                    r.get("original_path", f"/DCIM/{n}/{name}"),
                    r.get("device_name", "Pixel 8 Pro"),
                    r.get("file_size", 100 + n),
                    name,
                    r.get("exif_time"),
                    "2020-01-01 00:00:00" if created is _UNSET else created,
                    r.get("focal_length"),
                    r.get("deleted_at"),
                    r.get("purged_at"),
                    r.get("media_type", "image"),
                    key,
                    ext,
                    r.get("mime_type"),
                    r.get("capture_time"),
                    r.get("capture_time_kind"),
                    r.get("is_reference", 0),
                ),
            )
            ids.append(cursor.lastrowid)
        await db.commit()
    return ids


def _intervals(tz: str) -> tuple[tuple[str, str], ...]:
    rtz = ResolvedTz(ZoneInfo(tz), tz, "request", f"iana:{tz}")
    return utc_offset_intervals(rtz, datetime.now(timezone.utc).year + 1)


async def _connect(db_path: str) -> aiosqlite.Connection:
    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row
    return db


async def _run(db_path: str, method: str, *args, **kwargs):
    db = await _connect(db_path)
    try:
        return await getattr(TimelineService(db), method)(*args, **kwargs)
    finally:
        await db.close()


def _filters(
    q: Optional[str] = None,
    device: tuple = (),
    format: tuple = (),
    focal: tuple = (),
    date: Optional[tuple[str, str]] = None,
    dir_prefix: Optional[str] = None,
    recursive: bool = True,
    capture_date: Optional[str] = None,
) -> TimelineFilters:
    return TimelineFilters(
        keywords=tuple(parse_search_query(q)),
        devices=tuple(device),
        formats=tuple(format),
        focals=tuple(focal),
        date_from=date[0] if date else None,
        date_to=date[1] if date else None,
        dir_prefix=dir_prefix,
        dir_recursive=recursive,
        capture_date=capture_date,
    )


# ---------------------------------------------------------------------------
# Reference implementations (old Web front-end semantics)
# ---------------------------------------------------------------------------

_HAS_TZ_RE = re.compile(r"[Zz]|[+-]\d{2}:?\d{2}$")
_SQLITE_UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$")
_DATE_ONLY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def expected_local(s: Optional[str], tz: str) -> Optional[str]:
    """Browser-local 'YYYY-MM-DD HH:MM:SS' of ``new Date(normalizeTimestamp(s))``."""
    if s is None:
        return None
    try:
        if not _HAS_TZ_RE.search(s) and _SQLITE_UTC_RE.match(s):
            dt = datetime.fromisoformat(s.replace(" ", "T")).replace(tzinfo=timezone.utc)
        elif _DATE_ONLY_RE.match(s):
            dt = datetime.fromisoformat(s).replace(tzinfo=timezone.utc)
        else:
            dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    if dt.tzinfo is not None:
        dt = dt.astimezone(ZoneInfo(tz)).replace(tzinfo=None)
    out = dt.replace(microsecond=0).isoformat(sep=" ")
    return out if "1000-01-01" <= out[:10] <= "9998-12-31" else None


def ref_focal_bucket(focal: Optional[float]) -> str:
    if focal is None or focal <= 0:
        return "unknown"
    if focal <= 20:
        return "ultrawide"
    if focal <= 35:
        return "wide"
    if focal <= 70:
        return "standard"
    if focal <= 135:
        return "tele"
    return "supertele"


def ref_name_match(name: str, q: Optional[str]) -> bool:
    keywords = parse_search_query(q)
    norm = normalize_for_search(name)
    key = file_name_key(name)
    return all((k.value in norm) if k.raw else (k.value in key) for k in keywords)


def ref_key(row: dict) -> Optional[str]:
    """COALESCE(exif_time, created_at)."""
    created = row.get("created_at", "2020-01-01 00:00:00")
    return row.get("exif_time") if row.get("exif_time") is not None else created


def ref_match(row: dict, flt: dict, tz: str) -> bool:
    name = row["file_name"]
    if not ref_name_match(name, flt.get("q")):
        return False
    if flt.get("device") and row.get("device_name", "Pixel 8 Pro") not in flt["device"]:
        return False
    if flt.get("format") and file_ext(name) not in flt["format"]:
        return False
    if flt.get("focal") and ref_focal_bucket(row.get("focal_length")) not in flt["focal"]:
        return False
    if flt.get("date"):
        local = expected_local(ref_key(row), tz)
        if local is None or not (flt["date"][0] <= local[:10] <= flt["date"][1]):
            return False
    return True


def ref_active(rows: list[dict], user_id: int) -> list[dict]:
    return [
        r for r in rows
        if r.get("user_id", 1) == user_id
        and r.get("deleted_at") is None and r.get("purged_at") is None
    ]


def ref_months(rows: list[dict], tz: str) -> dict[tuple[int, int], list[dict]]:
    """Rows grouped by local month, each group in K DESC, id DESC order."""
    groups: dict[tuple[int, int], list[dict]] = {}
    for r in rows:
        local = expected_local(ref_key(r), tz)
        if local is None:
            continue
        groups.setdefault((int(local[:4]), int(local[5:7])), []).append(r)
    for g in groups.values():
        g.sort(key=lambda r: (ref_key(r), r["id"]), reverse=True)
    return groups


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------


def test_month_bounds():
    assert month_bounds(2024, 6) == (
        "2024-06-01 00:00:00", "2024-07-01 00:00:00", "2024-05-30", "2024-07-03",
    )
    assert month_bounds(2024, 12) == (
        "2024-12-01 00:00:00", "2025-01-01 00:00:00", "2024-11-29", "2025-01-03",
    )
    assert month_bounds(1000, 1)[2] == "0999-12-30"
    assert month_bounds(9998, 12)[1] == "9999-01-01 00:00:00"


def test_filters_sql_empty_and_active():
    assert TimelineFilters().sql() == ("1", [])
    assert not TimelineFilters().active
    assert not _filters(q="   ").active
    assert _filters(q="img").active
    sql, params = _filters(q="img _", device=("A", "B"), format=("JPG",),
                           focal=("wide",), date=("2024-01-01", "2024-01-31")).sql()
    assert params == ["img", "_", "A", "B", "JPG", "wide", "2024-01-01", "2024-01-31"]
    assert sql.count("?") == len(params)
    assert "LIKE" not in sql.upper()


def test_resolve_timeline_tz_prefers_request(db_path):
    settings = get_settings()
    rtz = resolve_timeline_tz("America/New_York", settings)
    assert (rtz.name, rtz.source) == ("America/New_York", "request")
    fallback = resolve_timeline_tz(None, settings)
    assert fallback.source in ("config", "server")


# ---------------------------------------------------------------------------
# V1.4 local time
# ---------------------------------------------------------------------------

LOCAL_TIME_CASES = [
    # (exif_time, created_at)
    (None, "2024-06-30 17:00:00"),        # UTC created_at across the month end
    (None, "2024-01-31 23:30:00"),
    (None, "2024-03-10 06:30:00"),        # New York DST start (07:00 UTC)
    (None, "2024-03-10 07:30:00"),
    (None, "2024-11-03 05:30:00"),        # New York DST end (06:00 UTC)
    (None, "2024-11-03 06:30:00"),
    (None, "2024-12-31 20:00:00.123"),    # fractional seconds, still UTC
    ("2024-05-01T10:00:00", None),        # Android wall-clock exif_time
    ("2024-05-31T23:59:59", None),
    ("2024-05-01T10:00:00.5", None),
    ("2024-05-01T23:30:00+05:00", None),  # explicit offset
    ("2024-05-31T20:00:00Z", None),       # Z suffix
    ("2024-06-01", None),                 # pure date: UTC midnight
]


@pytest.mark.parametrize("tz", TZS)
async def test_local_time_matches_browser(db_path, tz):
    rows = [{"exif_time": e, "created_at": c} for e, c in LOCAL_TIME_CASES]
    ids = await _insert_rows(db_path, rows)
    db = await _connect(db_path)
    try:
        await TimelineService(db)._prepare(_intervals(tz))
        cursor = await db.execute(_sql("SELECT fr.id, {LOCAL} FROM file_records AS fr"))
        got = {r[0]: r[1] for r in await cursor.fetchall()}
    finally:
        await db.close()
    for row_id, (exif, created) in zip(ids, LOCAL_TIME_CASES):
        expected = expected_local(exif if exif is not None else created, tz)
        assert expected is not None
        assert got[row_id] == expected, (exif, created, tz)


async def test_local_time_known_differences_are_no_date(db_path):
    # Browsers parse these, SQLite datetime() does not: the timeline treats
    # them as "no date" (documented in design §D2). Our clients never write them.
    ids = await _insert_rows(
        db_path,
        [
            {"exif_time": "2024-05-01T10:00:00+0500"},
            {"exif_time": "2024/05/01 12:00"},
            {"exif_time": ""},
            {"exif_time": None, "created_at": None},
        ],
    )
    db = await _connect(db_path)
    try:
        await TimelineService(db)._prepare(_intervals("Asia/Shanghai"))
        cursor = await db.execute(_sql("SELECT fr.id, {LOCAL} FROM file_records AS fr"))
        got = {r[0]: r[1] for r in await cursor.fetchall()}
    finally:
        await db.close()
    assert [got[i] for i in ids] == [None, None, None, None]
    summary = await _run(db_path, "summary", 1, TimelineFilters(), _intervals("UTC"))
    assert (summary.total_count, summary.months) == (0, [])


# ---------------------------------------------------------------------------
# V1.2 / V1.3 / V1.7 filters and aggregates against the reference
# ---------------------------------------------------------------------------

_NAMES = [
    "IMG_1234.JPG", "IMG_2024_0501.heic", "Screenshot_2024-05-01.png", "100%_done.png",
    "photo (1).jpg", "noext", "trailing.", ".hidden", "x.tar.gz", "照片.Jpg",
    "ＩＭＧ_５６７８.JPG", "mmexport1700000000000.jpg", "VID_20240101.mp4", LONG_DOTTED_NAME,
]
_DEVICES = ["Pixel 8 Pro", "iPhone 15 Pro", "", LONG_DEVICE]
_FOCALS = [0, -1, None, 20, 20.5, 35, 70, 135, 135.1, 200]
_TIMES = [
    (None, "2024-06-30 17:00:00"),
    ("2024-06-30T23:00:00", "2024-07-02 00:00:00"),
    ("2024-07-01T00:30:00", "2024-07-02 00:00:00"),
    (None, "2024-05-31 15:59:59"),
    ("2024-05-15T10:00:00", "2024-06-01 00:00:00"),
    ("2024-04-01T00:00:00+05:00", "2024-06-01 00:00:00"),
    ("2024-05-01T03:00:00Z", "2024-06-01 00:00:00"),
    ("2024-06-01", "2024-06-01 00:00:00"),
    (None, "2024-03-10 06:30:00"),
    (None, "2024-11-03 06:30:00"),
    ("", "2024-05-05 00:00:00"),           # empty exif_time: no date (A-1)
    (None, None),                          # no time at all
    ("2024-06-15T12:00:00", "2024-06-20 00:00:00"),
]


def _library_rows() -> list[dict]:
    rows = []
    for i in range(140):
        exif, created = _TIMES[i % len(_TIMES)]
        rows.append({
            "file_name": _NAMES[i % len(_NAMES)],
            "device_name": _DEVICES[i % len(_DEVICES)],
            "focal_length": _FOCALS[i % len(_FOCALS)],
            "exif_time": exif,
            "created_at": created,
        })
    # Isolation rows (V1.6): another user, trashed and purged rows. Unique
    # devices / formats / names / months so any leak is visible.
    rows += [
        {"user_id": 2, "file_name": "u2_secret.gif", "device_name": "OnlyU2",
         "exif_time": "2023-01-10T10:00:00", "focal_length": 50},
        {"user_id": 2, "file_name": "u2_secret2.gif", "device_name": "OnlyU2",
         "exif_time": "2024-06-10T10:00:00", "focal_length": 50},
        {"file_name": "trashed_secret.bmp", "device_name": "TrashCam",
         "exif_time": "2023-02-10T10:00:00", "deleted_at": "2024-07-01 00:00:00"},
        {"file_name": "purged_secret.tiff", "device_name": "PurgedCam",
         "exif_time": "2023-03-10T10:00:00", "deleted_at": "2024-07-01 00:00:00",
         "purged_at": "2024-07-02 00:00:00"},
    ]
    return rows


async def _seed_library(db_path: str) -> list[dict]:
    await _create_user(db_path, "alice")
    await _create_user(db_path, "bob")
    rows = _library_rows()
    ids = await _insert_rows(db_path, rows)
    for r, row_id in zip(rows, ids):
        r["id"] = row_id
    return rows


_FILTER_OPTIONS = {
    "q": ["img", "2024 05", "_", "%"],
    "device": [("Pixel 8 Pro",), ("iPhone 15 Pro", LONG_DEVICE)],
    "format": [("JPG",), ("PNG", "HEIC", LONG_EXT)],
    "focal": [("unknown",), ("ultrawide", "tele"), ("wide", "standard", "supertele")],
    "date": [("2024-05-01", "2024-06-30"), ("2024-07-01", "2024-07-01"),
             ("2024-06-02", "2024-06-01")],
}


def _filter_combos() -> list[dict]:
    combos: list[dict] = [{}]
    for key, values in _FILTER_OPTIONS.items():
        combos += [{key: v} for v in values]
    for a, b in itertools.combinations(_FILTER_OPTIONS, 2):
        combos.append({a: _FILTER_OPTIONS[a][0], b: _FILTER_OPTIONS[b][0]})
    combos.append({k: v[0] for k, v in _FILTER_OPTIONS.items()})
    combos.append({k: v[1] for k, v in _FILTER_OPTIONS.items()})
    combos.append({"q": "img", "format": ("JPG",), "focal": ("unknown", "wide"),
                   "date": ("2024-01-01", "2024-12-31")})
    return combos


@pytest.mark.parametrize("backfilled", [False, True])
@pytest.mark.parametrize("tz", ["Asia/Shanghai", "America/New_York"])
async def test_filters_and_aggregates_match_reference(db_path, tz, backfilled):
    rows = await _seed_library(db_path)
    if backfilled:
        async with aiosqlite.connect(db_path) as db:
            assert await run_filename_key_backfill_round(db) == len(rows)
    intervals = _intervals(tz)
    active = ref_active(rows, 1)
    all_months = ref_months(active, tz)
    total = sum(len(g) for g in all_months.values())
    assert total < len(active)  # some rows have no date and are never counted

    db = await _connect(db_path)
    try:
        svc = TimelineService(db)
        for flt in _filter_combos():
            filters = _filters(**flt)
            matched = [r for r in active if ref_match(r, flt, tz)]
            months = ref_months(matched, tz)
            summary = await svc.summary(1, filters, intervals)
            assert summary.total_count == total, flt
            assert [(m.year, m.month, m.count) for m in summary.months] == [
                (y, m, len(g)) for (y, m), g in sorted(months.items(), reverse=True)
            ], flt
            assert summary.filtered_count == len([
                r for g in months.values() for r in g
            ]), flt
            for (y, m), group in months.items():
                files, has_more = await svc.list_month(1, filters, intervals, y, m, limit=500)
                assert [f.id for f in files] == [r["id"] for r in group], (flt, y, m)
                assert has_more is False

        facets = await svc.facets(1, intervals)
    finally:
        await db.close()

    assert facets.devices == sorted({r["device_name"] for r in active if r["device_name"]})
    assert "" not in facets.devices and LONG_DEVICE in facets.devices
    assert facets.formats == sorted({file_ext(r["file_name"]) for r in active} - {""})
    assert LONG_EXT in facets.formats
    assert facets.focal_buckets == [
        b for b in ("ultrawide", "wide", "standard", "tele", "supertele", "unknown")
        if b in {ref_focal_bucket(r["focal_length"]) for r in active}
    ]
    assert facets.photo_dates == sorted({
        expected_local(ref_key(r), tz)[:10]
        for r in active if expected_local(ref_key(r), tz) is not None
    })


def test_focal_bucket_boundaries():
    assert [ref_focal_bucket(f) for f in _FOCALS] == [
        "unknown", "unknown", "unknown", "ultrawide", "wide", "wide",
        "standard", "tele", "supertele", "supertele",
    ]


async def test_focal_sql_matches_reference_buckets(db_path):
    ids = await _insert_rows(
        db_path, [{"focal_length": f, "exif_time": "2024-06-15T12:00:00"} for f in _FOCALS]
    )
    expected = {i: ref_focal_bucket(f) for i, f in zip(ids, _FOCALS)}
    for bucket in set(expected.values()):
        files, _ = await _run(db_path, "list_month", 1, _filters(focal=(bucket,)),
                              _intervals("UTC"), 2024, 6, limit=500)
        assert sorted(f.id for f in files) == sorted(i for i, b in expected.items() if b == bucket)


async def test_format_filter_skips_names_without_extension(db_path):
    await _insert_rows(db_path, [
        {"file_name": n, "exif_time": "2024-06-15T12:00:00"}
        for n in ("noext", "trailing.", "a.jpg")
    ])
    facets = await _run(db_path, "facets", 1, _intervals("UTC"))
    assert facets.formats == ["JPG"]  # '' is never offered as an option
    # Selecting every offered format still excludes names without an extension.
    summary = await _run(db_path, "summary", 1, _filters(format=tuple(facets.formats)),
                         _intervals("UTC"))
    assert (summary.total_count, summary.filtered_count) == (3, 1)


async def test_search_literal_and_raw_keywords(db_path):
    names = ["100%_done.png", "IMG_1234.JPG", "photo.jpg", "a-b.jpg",
             "Screenshot_2024-01-01.png", "Screenshot_2023-01-01.png"]
    ids = await _insert_rows(db_path, [
        {"file_name": n, "exif_time": "2024-06-15T12:00:00"} for n in names
    ])
    by_name = dict(zip(names, ids))

    async def hits(q):
        files, _ = await _run(db_path, "list_month", 1, _filters(q=q), _intervals("UTC"),
                              2024, 6, limit=500)
        return sorted(f.id for f in files)

    assert await hits("%") == [by_name["100%_done.png"]]
    assert await hits("_") == sorted([by_name["100%_done.png"], by_name["IMG_1234.JPG"],
                                      by_name["Screenshot_2024-01-01.png"],
                                      by_name["Screenshot_2023-01-01.png"]])
    assert await hits("screenshot 2024") == [by_name["Screenshot_2024-01-01.png"]]
    assert await hits(" \u3000 ") == sorted(ids)
    assert await hits("ＩＭＧ_１２") == [by_name["IMG_1234.JPG"]]


# ---------------------------------------------------------------------------
# V1.5 paging
# ---------------------------------------------------------------------------


def _paging_rows() -> list[dict]:
    rows = []
    for i in range(450):  # all with the same time key
        rows.append({
            "file_name": f"IMG_{i:04d}.JPG" if i % 3 else f"other_{i}.png",
            "device_name": "A" if i % 2 else "B",
            "exif_time": "2024-06-15T12:00:00",
        })
    for i in range(5):  # same UTC created_at: July in Shanghai, June in UTC
        rows.append({"file_name": f"IMG_utc_{i}.JPG", "device_name": "A",
                     "exif_time": None, "created_at": "2024-06-30 17:00:00"})
    for i in range(5):
        rows.append({"file_name": f"IMG_end_{i}.JPG", "device_name": "A",
                     "exif_time": "2024-06-30T23:00:00"})
    for i in range(5):
        rows.append({"file_name": f"IMG_jul_{i}.JPG", "device_name": "B",
                     "exif_time": "2024-07-01T00:00:00"})
    return rows


async def _page_all(db, filters, intervals, year, month, mode, limit=7):
    svc = TimelineService(db)
    out, pages = [], []
    after, offset = None, 0
    while True:
        files, more = await svc.list_month(
            1, filters, intervals, year, month, offset=offset, after=after, limit=limit
        )
        out.extend(files)
        pages.append((len(files), more))
        if not more:
            return out, pages
        last = files[-1]
        if mode == "keyset":
            after = TimelineCursor(
                last.exif_time if last.exif_time is not None else last.created_at, last.id
            )
        else:
            offset += limit


@pytest.mark.parametrize("tz", ["Asia/Shanghai", "UTC"])
@pytest.mark.parametrize("mode", ["keyset", "offset"])
@pytest.mark.parametrize("flt", [{}, {"q": "img", "device": ("A",)}])
async def test_paging_equal_times_no_duplicates_no_gaps(db_path, tz, mode, flt):
    rows = _paging_rows()
    for r, row_id in zip(rows, await _insert_rows(db_path, rows)):
        r["id"] = row_id
    intervals = _intervals(tz)
    matched = [r for r in rows if ref_match(r, flt, tz)]
    expected = ref_months(matched, tz)
    db = await _connect(db_path)
    try:
        for (y, m) in [(2024, 6), (2024, 7)]:
            files, pages = await _page_all(db, _filters(**flt), intervals, y, m, mode)
            ids = [f.id for f in files]
            assert len(ids) == len(set(ids))
            assert ids == [r["id"] for r in expected.get((y, m), [])], (y, m)
            assert pages[-1][1] is False
            assert all(n == 7 and more for n, more in pages[:-1])
    finally:
        await db.close()


def test_list_month_sql_structure():
    k = _K
    flt = _filters(q="img", device=("A", "B"))
    filter_sql, filter_params = flt.sql()
    month_start, next_month_start, env_lo, env_hi = month_bounds(2024, 6)
    t = "2024-06-15T12:00:00"

    sql, params = TimelineService._list_month_sql(
        1, flt, 2024, 6, 0, TimelineCursor(t, 42), 7
    )
    assert f"AND {k} < ?" not in sql
    assert sql.count(f"{k} <= ?") == 1
    assert env_hi not in params
    assert params == [1, env_lo, t, t, t, 42, month_start, next_month_start,
                      *filter_params, 8, 0]
    assert sql.count("?") == len(params)
    assert "INDEXED BY idx_file_records_timeline" in sql

    sql, params = TimelineService._list_month_sql(1, flt, 2024, 6, 21, None, 7)
    assert sql.count(f"AND {k} < ?") == 1
    assert f"{k} <=" not in sql
    assert params == [1, env_lo, env_hi, month_start, next_month_start,
                      *filter_params, 8, 21]
    assert sql.count("?") == len(params)


async def test_files_plan_uses_timeline_index(db_path):
    await _insert_rows(db_path, _paging_rows())
    db = await _connect(db_path)
    try:
        await TimelineService(db)._prepare(_intervals("Asia/Shanghai"))
        for after in (None, TimelineCursor("2024-06-15T12:00:00", 100)):
            sql, params = TimelineService._list_month_sql(
                1, TimelineFilters(), 2024, 6, 0, after, 200
            )
            cursor = await db.execute(f"EXPLAIN QUERY PLAN {sql}", params)
            plan = " | ".join(r[3] for r in await cursor.fetchall())
            assert "USING INDEX idx_file_records_timeline" in plan
            assert "TEMP B-TREE FOR ORDER BY" not in plan
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# V1.6 / V1.7 isolation and the summary parameter order
# ---------------------------------------------------------------------------


async def test_isolation_other_user_trash_purged(db_path):
    rows = await _seed_library(db_path)
    intervals = _intervals("Asia/Shanghai")
    facets = await _run(db_path, "facets", 1, intervals)
    for leaked in ("OnlyU2", "TrashCam", "PurgedCam"):
        assert leaked not in facets.devices
    for leaked in ("GIF", "BMP", "TIFF"):
        assert leaked not in facets.formats
    assert not [d for d in facets.photo_dates if d.startswith("2023-")]

    summary = await _run(db_path, "summary", 1, TimelineFilters(), intervals)
    assert all(m.year != 2023 for m in summary.months)
    assert summary.total_count == sum(
        len(g) for g in ref_months(ref_active(rows, 1), "Asia/Shanghai").values()
    )
    for q in ("secret", "u2"):
        s = await _run(db_path, "summary", 1, _filters(q=q), intervals)
        assert (s.filtered_count, s.months) == (0, [])
    for y, m in [(2023, 1), (2023, 2), (2023, 3)]:
        files, _ = await _run(db_path, "list_month", 1, TimelineFilters(), intervals, y, m)
        assert files == []
    files, _ = await _run(db_path, "list_month", 1, _filters(q="secret"), intervals, 2024, 6)
    assert files == []

    # Filter values only present in user 2's data: with [*filter_params, user_id]
    # in the right order user 1 gets 0 matches and its own total.
    s1 = await _run(db_path, "summary", 1, _filters(device=("OnlyU2",)), intervals)
    assert (s1.filtered_count, s1.months, s1.total_count) == (0, [], summary.total_count)
    s2 = await _run(db_path, "summary", 2, _filters(device=("OnlyU2",)), intervals)
    assert (s2.total_count, s2.filtered_count) == (2, 2)
    assert [(m.year, m.month, m.count) for m in s2.months] == [(2024, 6, 1), (2023, 1, 1)]
    f2 = await _run(db_path, "facets", 2, intervals)
    assert (f2.devices, f2.formats) == (["OnlyU2"], ["GIF"])


# ---------------------------------------------------------------------------
# V1.8 migration, backfill, trigger, write paths
# ---------------------------------------------------------------------------

_LEGACY_DDL = database._CREATE_FILE_RECORDS_TABLE.replace(
    "    file_name_key TEXT,\n    file_ext TEXT,\n", ""
)


async def test_legacy_database_migration_backfill_and_trigger(tmp_path, caplog, monkeypatch):
    # setup_logging (run by other tests) stops propagation to caplog's handler.
    monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
    assert "file_name_key" not in _LEGACY_DDL and "file_ext" not in _LEGACY_DDL
    path = str(tmp_path / "legacy.db")
    names = ["IMG_0001.JPG", "Screenshot_2024.png", "noext", "照片.Jpg"]
    async with aiosqlite.connect(path) as db:
        await db.execute(_LEGACY_DDL)
        for i, name in enumerate(names):
            await db.execute(
                """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
                       device_name, file_size, file_name, exif_time)
                   VALUES (1, ?, ?, ?, 'Pixel', 1, ?, '2024-06-15T12:00:00')""",
                (f"h{i}", f"/m/{i}/{name}", f"/o/{i}/{name}", name),
            )
        await db.commit()

    await init_db(path)
    await init_db(path)

    async with aiosqlite.connect(path) as db:
        cursor = await db.execute("PRAGMA table_info(file_records)")
        cols = [r[1] for r in await cursor.fetchall()]
        assert cols[-2:] == ["file_name_key", "file_ext"]
        cursor = await db.execute(
            "SELECT type, name FROM sqlite_master WHERE name IN "
            "('idx_file_records_timeline', 'trg_file_records_name_derived_stale')"
        )
        assert sorted(await cursor.fetchall()) == [
            ("index", "idx_file_records_timeline"),
            ("trigger", "trg_file_records_name_derived_stale"),
        ]
        cursor = await db.execute("SELECT COUNT(*) FROM file_records WHERE file_name_key IS NULL")
        assert (await cursor.fetchone())[0] == len(names)

    intervals = _intervals("UTC")

    async def counts(**flt):
        return (await _run(path, "summary", 1, _filters(**flt), intervals)).filtered_count

    # Before the backfill: Python fallback functions.
    assert await counts(q="img 0001") == 1
    assert await counts(format=("JPG",)) == 2
    assert (await _run(path, "facets", 1, intervals)).formats == ["JPG", "PNG"]

    caplog.set_level(logging.INFO, logger="photovault.filename_key_backfill")
    async with aiosqlite.connect(path) as db:
        assert await run_filename_key_backfill_round(db) == len(names)
        assert await run_filename_key_backfill_round(db) == 0
        cursor = await db.execute("SELECT file_name, file_name_key, file_ext FROM file_records")
        for name, key, ext in await cursor.fetchall():
            assert (key, ext) == derived_name_columns(name)
    assert sum("File name search keys backfilled: rows=4" in r.getMessage()
               for r in caplog.records) == 1

    assert await counts(q="img 0001") == 1
    assert await counts(format=("JPG",)) == 2

    # Rename without the derived columns: the trigger invalidates them.
    async with aiosqlite.connect(path) as db:
        await db.execute("UPDATE file_records SET file_name = 'new.png' WHERE file_hash = 'h0'")
        await db.commit()
        cursor = await db.execute(
            "SELECT file_name_key, file_ext FROM file_records WHERE file_hash = 'h0'"
        )
        assert tuple(await cursor.fetchone()) == (None, None)
        # Rename that also writes the derived columns keeps them.
        await db.execute(
            "UPDATE file_records SET file_name = 'b.gif', file_name_key = 'bgif', "
            "file_ext = 'GIF' WHERE file_hash = 'h1'"
        )
        await db.commit()
        cursor = await db.execute(
            "SELECT file_name_key, file_ext FROM file_records WHERE file_hash = 'h1'"
        )
        assert tuple(await cursor.fetchone()) == ("bgif", "GIF")
    assert await counts(q="new") == 1
    assert await counts(format=("PNG",)) == 1
    assert await counts(q="img 0001") == 0
    async with aiosqlite.connect(path) as db:
        assert await run_filename_key_backfill_round(db) == 1


async def test_write_paths_fill_derived_columns(db_path):
    user_id, _ = await _create_user(db_path, "alice")
    db = await _connect(db_path)
    try:
        svc = DeduplicationService(db)
        rec = await svc.register_file(
            user_id=user_id, file_hash="h1", file_path="/m/alice/Dev/IMG_0001.JPG",
            original_path="/DCIM/IMG_0001.JPG", device_name="Pixel", file_size=1,
            file_name="IMG_0001.JPG",
        )
        ref = await svc.create_reference(
            user_id=user_id, file_hash="h1", target_path="/m/alice/Dev/Copy (2).HEIC",
            source_record_id=rec.id, original_path="/DCIM/Copy (2).HEIC",
            device_name="Pixel", file_size=1, file_name="Copy (2).HEIC",
        )
        await db.execute(
            """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
                   device_name, file_size, file_name, file_name_key, file_ext,
                   deleted_at)
               VALUES (?, 'h9', '/m/alice/.trash/Dev/old.jpg', '/DCIM/old.jpg', 'Pixel',
                       1, 'old.jpg', 'oldjpg', 'JPG', '2024-01-01 00:00:00')""",
            (user_id,),
        )
        await db.commit()
        react = await svc.register_or_reactivate_file(
            user_id=user_id, file_hash="h9", file_path="/m/alice/Dev/Renamed-File.PNG",
            original_path="/DCIM/Renamed-File.PNG", device_name="Pixel", file_size=1,
            file_name="Renamed-File.PNG",
        )
        cursor = await db.execute(
            "SELECT id, file_name, file_name_key, file_ext FROM file_records ORDER BY id"
        )
        got = {r[0]: (r[1], r[2], r[3]) for r in await cursor.fetchall()}
    finally:
        await db.close()
    for record_id in (rec.id, ref.id, react.id):
        name, key, ext = got[record_id]
        assert key is not None and ext is not None
        assert (key, ext) == derived_name_columns(name)
    assert got[react.id] == ("Renamed-File.PNG", "renamedfilepng", "PNG")


# ---------------------------------------------------------------------------
# V1.9 endpoints
# ---------------------------------------------------------------------------

FACETS_URL = "/files/timeline/facets"
SUMMARY_URL = "/files/timeline/summary"
FILES_URL = "/files/timeline/files"


async def test_endpoints_response_shapes(db_path, client):
    _, token = await _create_user(db_path, "alice")
    await _insert_rows(db_path, [
        {"file_name": "IMG_0001.JPG", "device_name": LONG_DEVICE, "focal_length": 26,
         "exif_time": "2024-06-15T12:00:00"},
        {"file_name": LONG_DOTTED_NAME, "device_name": "Pixel 8 Pro",
         "exif_time": None, "created_at": "2024-06-30 17:00:00"},
    ])
    h = _auth(token)

    r = await client.get(FACETS_URL, params={"tz": "Asia/Shanghai"}, headers=h)
    assert r.status_code == 200
    assert r.json() == {
        "tz": "Asia/Shanghai",
        "devices": sorted([LONG_DEVICE, "Pixel 8 Pro"]),
        "formats": sorted(["JPG", LONG_EXT]),
        "focal_buckets": ["wide", "unknown"],
        "photo_dates": ["2024-06-15", "2024-07-01"],
    }

    r = await client.get(SUMMARY_URL, params={"tz": "Asia/Shanghai"}, headers=h)
    assert r.status_code == 200
    assert r.json() == {
        "tz": "Asia/Shanghai",
        "total_count": 2,
        "filtered_count": 2,
        "months": [{"year": 2024, "month": 7, "count": 1},
                   {"year": 2024, "month": 6, "count": 1}],
    }

    # Long facet values are accepted as filters (no per-value length limit).
    r = await client.get(
        SUMMARY_URL,
        params=[("tz", "Asia/Shanghai"), ("device", LONG_DEVICE), ("device", "Nope")],
        headers=h,
    )
    assert r.status_code == 200 and r.json()["filtered_count"] == 1
    r = await client.get(SUMMARY_URL, params={"tz": "UTC", "format": LONG_EXT}, headers=h)
    assert r.status_code == 200
    assert r.json()["months"] == [{"year": 2024, "month": 6, "count": 1}]

    r = await client.get(FILES_URL, params={"tz": "Asia/Shanghai", "month": "2024-06"},
                         headers=h)
    assert r.status_code == 200
    body = r.json()
    assert body["month"] == "2024-06" and body["has_more"] is False
    [item] = body["files"]
    assert set(item) == {
        "id", "file_name", "file_size", "mime_type", "exif_time", "media_type",
        "is_motion_photo", "is_ultra_hdr", "created_at", "thumbnail_url",
        "device_name", "focal_length",
    }
    assert item["thumbnail_url"] == f"/api/v1/files/thumbnail/{item['id']}"
    assert (item["file_name"], item["device_name"], item["focal_length"]) == (
        "IMG_0001.JPG", LONG_DEVICE, 26.0,
    )

    # No tz: server default, still a valid response.
    r = await client.get(SUMMARY_URL, headers=h)
    assert r.status_code == 200 and isinstance(r.json()["tz"], str)


async def test_files_endpoint_keyset_with_filters(db_path, client):
    _, token = await _create_user(db_path, "alice")
    rows = _paging_rows()
    for r, row_id in zip(rows, await _insert_rows(db_path, rows)):
        r["id"] = row_id
    flt = {"q": "img", "device": ("A",)}
    expected = [r["id"] for r in ref_months(
        [r for r in rows if ref_match(r, flt, "Asia/Shanghai")], "Asia/Shanghai"
    )[(2024, 6)]]
    got, params = [], {"tz": "Asia/Shanghai", "month": "2024-06", "q": "img",
                       "device": "A", "limit": 50}
    while True:
        r = await client.get(FILES_URL, params=params, headers=_auth(token))
        assert r.status_code == 200
        body = r.json()
        got += [f["id"] for f in body["files"]]
        if not body["has_more"]:
            break
        last = body["files"][-1]
        params = {**params, "after_time": last["exif_time"] or last["created_at"],
                  "after_id": last["id"]}
    assert got == expected


@pytest.mark.parametrize(
    ("url", "params", "code"),
    [
        (FACETS_URL, {"tz": "Mars/Olympus_Mons"}, "invalid_tz"),
        (SUMMARY_URL, {"tz": "Not a zone"}, "invalid_tz"),
        (FILES_URL, {"month": "0999-01"}, "invalid_month"),
        (FILES_URL, {"month": "2024-13"}, None),
        (FILES_URL, {"month": "2024-6"}, None),
        (FILES_URL, {}, None),
        (FILES_URL, {"month": "2024-06", "limit": 501}, None),
        (FILES_URL, {"month": "2024-06", "limit": 0}, None),
        (FILES_URL, {"month": "2024-06", "offset": -1}, None),
        (FILES_URL, {"month": "2024-06", "after_time": "2024-06-15T12:00:00"}, "invalid_cursor"),
        (FILES_URL, {"month": "2024-06", "after_id": 3}, "invalid_cursor"),
        (FILES_URL, {"month": "2024-06", "after_time": "2024-06-15T12:00:00",
                     "after_id": 3, "offset": 7}, "invalid_cursor"),
        (FILES_URL, {"month": "2024-06", "after_time": "x" * 65, "after_id": 3}, None),
        (SUMMARY_URL, {"focal": "foo"}, None),
        (SUMMARY_URL, {"date_from": "2024-01-01"}, "invalid_date_range"),
        (SUMMARY_URL, {"date_to": "2024-01-01"}, "invalid_date_range"),
        (SUMMARY_URL, {"date_from": "2024-1-1", "date_to": "2024-01-02"}, None),
        (SUMMARY_URL, {"q": "x" * 201}, None),
        (SUMMARY_URL, [("device", f"d{i}") for i in range(51)], "too_many_values"),
        (SUMMARY_URL, [("format", f"F{i}") for i in range(51)], "too_many_values"),
        (FILES_URL, [("month", "2024-06")] + [("format", f"F{i}") for i in range(51)],
         "too_many_values"),
    ],
)
async def test_endpoint_validation_errors(db_path, client, url, params, code):
    _, token = await _create_user(db_path, "alice")
    r = await client.get(url, params=params, headers=_auth(token))
    assert r.status_code == 422, r.text
    if code is not None:
        assert r.json()["detail"]["code"] == code


async def test_endpoint_accepts_limits(db_path, client):
    _, token = await _create_user(db_path, "alice")
    h = _auth(token)
    params = [("q", "x" * 200), ("date_from", "2024-06-02"), ("date_to", "2024-06-01")]
    params += [("device", f"d{i}") for i in range(50)] + [("format", f"F{i}") for i in range(50)]
    params += [("focal", "unknown"), ("focal", "unknown"), ("focal", "wide")]
    r = await client.get(SUMMARY_URL, params=params, headers=h)
    assert r.status_code == 200 and r.json()["filtered_count"] == 0
    r = await client.get(FILES_URL, params={"month": "9998-12", "limit": 500,
                                            "offset": 1_000_000}, headers=h)
    assert r.status_code == 200 and r.json()["files"] == []


async def test_endpoints_require_auth(db_path, client):
    for url in (FACETS_URL, SUMMARY_URL, f"{FILES_URL}?month=2024-06"):
        r = await client.get(url)
        assert r.status_code in (401, 403)


@pytest.mark.parametrize(
    ("method", "url"),
    [("facets", FACETS_URL), ("summary", SUMMARY_URL),
     ("list_month", f"{FILES_URL}?month=2024-06")],
)
async def test_database_error_returns_500_and_logs(db_path, client, monkeypatch, caplog,
                                                   method, url):
    _, token = await _create_user(db_path, "alice")

    async def _boom(*args, **kwargs):
        raise aiosqlite.OperationalError("disk I/O error")

    monkeypatch.setattr(TimelineService, method, _boom)
    # setup_logging (run by other tests) stops propagation to caplog's handler.
    monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
    caplog.set_level(logging.ERROR, logger="photovault.api.files")
    r = await client.get(url, headers=_auth(token))
    assert r.status_code == 500
    assert r.json() == {"detail": "时间线查询失败，请稍后重试"}
    errors = [rec for rec in caplog.records
              if rec.levelno == logging.ERROR and rec.name == "photovault.api.files"]
    assert len(errors) == 1 and errors[0].exc_info is not None
    assert "user_id=" in errors[0].getMessage()
    if method == "list_month":
        assert "month=2024-06" in errors[0].getMessage()


# ---------------------------------------------------------------------------
# V1.10 /files/all and /files/list regression (AC-1), INDEXED BY guard
# ---------------------------------------------------------------------------


async def _seed_browse_rows(db_path: str) -> tuple[int, list[str]]:
    user_id, _ = await _create_user(db_path, "alice")
    base = f"{get_settings().media_root}/alice"
    dirs = ["DevA/DCIM", "DevB/Camera", "DevA/WeChat"]
    rows = []
    for i in range(1000):
        d = dirs[i % 3]
        if i < 300:  # pairs with an identical COALESCE(exif_time, created_at)
            exif, created = f"2023-01-{(i // 2) % 28 + 1:02d}T10:{(i // 56):02d}:00", "2024-01-01 00:00:00"
        elif i % 4 == 0:
            exif, created = None, f"2024-{i % 12 + 1:02d}-{i % 28 + 1:02d} 0{i % 10}:00:00"
        else:
            exif, created = f"2022-{i % 12 + 1:02d}-{i % 27 + 1:02d}T{i % 24:02d}:00:00", "2024-01-01 00:00:00"
        rows.append({"user_id": user_id, "file_name": f"IMG_{i:04d}.jpg",
                     "file_path": f"{base}/{d}/IMG_{i:04d}.jpg", "file_size": (i * 37) % 1000,
                     "exif_time": exif, "created_at": created})
    for i in range(30):
        purged = i >= 20
        rows.append({"user_id": user_id, "file_name": f"T_{i:02d}.jpg",
                     "file_path": f"{base}/.trash/{dirs[i % 3]}/T_{i:02d}.jpg",
                     "exif_time": f"2023-01-0{i % 9 + 1}T10:00:00",
                     "deleted_at": "2024-07-01 00:00:00",
                     "purged_at": "2024-07-02 00:00:00" if purged else None})
    await _insert_rows(db_path, rows)
    return user_id, ["", "DevA", "DevB", *dirs]


async def _browse_snapshot(db_path: str, user_id: int, paths: list[str]):
    """Every page of /files/all and /files/list plus the plans of every SELECT."""
    db = await _connect(db_path)
    statements: list[str] = []
    await db.set_trace_callback(statements.append)
    try:
        svc = FileBrowseService(db, get_settings().media_root)
        results = {}
        page, seq, total = 1, [], None
        while True:
            listing = await svc.list_all_files(user_id, page=page, page_size=37, sort_by="time")
            total = listing.total_files
            seq += [dataclasses.asdict(f) for f in listing.files]
            if page * 37 >= total:
                break
            page += 1
        results["all"] = (total, seq)
        for path in paths:
            for sort_by in ("time", "name", "size"):
                page, pages = 1, []
                while True:
                    listing = await svc.list_directory(
                        user_id, path=path, page=page, page_size=37, sort_by=sort_by
                    )
                    pages.append(dataclasses.asdict(listing))
                    if page * 37 >= listing.total_files:
                        break
                    page += 1
                results[(path, sort_by)] = pages
        await db.set_trace_callback(None)
        plans = {}
        for stmt in dict.fromkeys(statements):
            if stmt.lstrip().upper().startswith("SELECT"):
                cursor = await db.execute(f"EXPLAIN QUERY PLAN {stmt}")
                plans[stmt] = [tuple(r)[1:] for r in await cursor.fetchall()]
    finally:
        await db.close()
    return results, plans


async def test_files_all_and_list_unchanged_by_timeline_index(db_path):
    user_id, paths = await _seed_browse_rows(db_path)
    async with aiosqlite.connect(db_path) as db:
        await db.execute("DROP INDEX idx_file_records_timeline")
        await db.commit()
    before, plans_before = await _browse_snapshot(db_path, user_id, paths)

    await init_db(db_path)  # rebuilds the index
    async with aiosqlite.connect(db_path) as db:
        cursor = await db.execute(
            "SELECT 1 FROM sqlite_master WHERE name = 'idx_file_records_timeline'"
        )
        assert await cursor.fetchone() is not None
    after, plans_after = await _browse_snapshot(db_path, user_id, paths)

    assert before["all"][0] == after["all"][0] == 1000
    assert len(before["all"][1]) == 1000
    assert before == after
    assert plans_before and plans_before == plans_after
    assert not any("idx_file_records_timeline" in str(p) for p in plans_after.values())


async def test_list_month_requires_timeline_index(db_path):
    await _insert_rows(db_path, [{"exif_time": "2024-06-15T12:00:00"}])
    async with aiosqlite.connect(db_path) as db:
        await db.execute("DROP INDEX idx_file_records_timeline")
        await db.commit()
    with pytest.raises(sqlite3.OperationalError, match="no such index"):
        await _run(db_path, "list_month", 1, TimelineFilters(), _intervals("UTC"), 2024, 6)
    await init_db(db_path)
    files, has_more = await _run(
        db_path, "list_month", 1, TimelineFilters(), _intervals("UTC"), 2024, 6
    )
    assert len(files) == 1 and has_more is False


# ---------------------------------------------------------------------------
# Folder filter (path + recursive, same scope as /stats/media)
# ---------------------------------------------------------------------------

_T = "2024-06-15T12:00:00"


def _folder_rows() -> list[dict]:
    paths = [
        "/m/u/a_b/1.jpg",
        "/m/u/a_b/sub/2.jpg",
        "/m/u/a_b/sub/deeper/3.jpg",
        "/m/u/a_b0/4.jpg",       # right after the prefix range
        "/m/u/axb/5.jpg",
        "/m/u/a_b.jpg",          # same name without the trailing '/'
        "/m/u/a_b.x/6.jpg",
        "/mnt/custom/7.jpg",     # custom path
    ]
    return [{"file_path": p, "file_name": p.rsplit("/", 1)[-1], "exif_time": _T}
            for p in paths]


async def test_folder_filter_prefix_boundaries(db_path):
    rows = _folder_rows()
    ids = await _insert_rows(db_path, rows)
    by_path = {r["file_path"]: i for r, i in zip(rows, ids)}
    intervals = _intervals("UTC")

    async def got(flt: TimelineFilters) -> tuple[list[int], int]:
        files, _ = await _run(db_path, "list_month", 1, flt, intervals, 2024, 6, limit=500)
        summary = await _run(db_path, "summary", 1, flt, intervals)
        assert summary.total_count == len(rows)  # library-wide, unfiltered
        assert summary.filtered_count == len(files)
        if files:
            assert [(m.year, m.month, m.count) for m in summary.months] == [(2024, 6, len(files))]
        return sorted(f.id for f in files), summary.filtered_count

    rec = sorted(i for p, i in by_path.items() if p.startswith("/m/u/a_b/"))
    assert (await got(_filters(dir_prefix="/m/u/a_b/")))[0] == rec
    assert (await got(_filters(dir_prefix="/m/u/a_b/", recursive=False)))[0] == [
        by_path["/m/u/a_b/1.jpg"]
    ]
    assert (await got(_filters(dir_prefix="/m/u/a_b/sub/", recursive=False)))[0] == [
        by_path["/m/u/a_b/sub/2.jpg"]
    ]
    assert (await got(_filters(dir_prefix="/m/u/a_b0/")))[0] == [by_path["/m/u/a_b0/4.jpg"]]
    assert (await got(_filters(dir_prefix="/m/u/nope/")))[1] == 0
    # No folder filter: everything, custom paths included.
    assert (await got(TimelineFilters()))[0] == sorted(ids)


def test_folder_filter_sql_params():
    flt = _filters(q="img", dir_prefix="/m/u/a_b/", recursive=False)
    sql, params = flt.sql()
    assert params == ["img", "/m/u/a_b/", "/m/u/a_b0", len("/m/u/a_b/") + 1]
    assert sql.count("?") == len(params)
    assert flt.active and _filters(dir_prefix="/m/u/x/").active
    assert _filters(capture_date="2024-06-15").active


@pytest.mark.parametrize("mode", ["keyset", "offset"])
@pytest.mark.parametrize("recursive", [True, False])
async def test_folder_filter_paging_no_duplicates_no_gaps(db_path, mode, recursive):
    rows = []
    for i in range(120):  # one time key for all: ties broken by id only
        folder = ("/m/u/A/", "/m/u/A/sub/", "/m/u/B/")[i % 3]
        rows.append({"file_path": f"{folder}IMG_{i:04d}.JPG", "file_name": f"IMG_{i:04d}.JPG",
                     "device_name": "A" if i % 2 else "B", "exif_time": _T})
    for r, row_id in zip(rows, await _insert_rows(db_path, rows)):
        r["id"] = row_id
    flt = _filters(dir_prefix="/m/u/A/", recursive=recursive, device=("A",))
    expected = sorted(
        (r["id"] for r in rows
         if r["device_name"] == "A"
         and (r["file_path"].startswith("/m/u/A/") if recursive
              else r["file_path"].startswith("/m/u/A/IMG_"))),
        reverse=True,
    )
    db = await _connect(db_path)
    try:
        files, pages = await _page_all(db, flt, _intervals("UTC"), 2024, 6, mode)
    finally:
        await db.close()
    assert [f.id for f in files] == expected
    assert len(pages) > 2 and pages[-1][1] is False


# ---------------------------------------------------------------------------
# capture_date filter (/stats/media rule)
# ---------------------------------------------------------------------------


async def _capture_ids(db_path, flt, capture_tz, tz="UTC", month=None):
    """(sorted ids from summary-listed months, filtered_count) for one filter."""
    cap = _intervals(capture_tz)
    intervals = _intervals(tz)
    summary = await _run(db_path, "summary", 1, flt, intervals, capture_intervals=cap)
    ids = []
    for m in summary.months:
        files, more = await _run(db_path, "list_month", 1, flt, intervals, m.year, m.month,
                                 limit=500, capture_intervals=cap)
        assert more is False
        ids += [f.id for f in files]
    return sorted(ids), summary.filtered_count


async def test_capture_date_kinds_dst_and_exclusions(db_path):
    future = datetime.now(timezone.utc).year + 5
    rows = [
        {"capture_time": "2025-01-15T23:30:00", "capture_time_kind": "local"},        # 0
        {"file_name": "v.mp4", "media_type": "video",
         "capture_time": "2025-01-15T18:30:00", "capture_time_kind": "utc"},          # 1
        {"capture_time": "1988-07-01T15:30:00", "capture_time_kind": "utc"},          # 2
        {"capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local",
         "is_reference": 1},                                                          # 3
        {"file_path": "/m/u/.trash/D/x.jpg", "file_name": "x.jpg",
         "capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local"},        # 4
        {"file_name": "notes.txt",
         "capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local"},        # 5
        {"file_name": "blob.bin", "mime_type": "image/jpeg",
         "capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local"},        # 6
        {"capture_time": "2025-01-15Tgarbage", "capture_time_kind": "local"},         # 7
        {"capture_time": "1800-06-01T10:00:00", "capture_time_kind": "local"},        # 8
        {"capture_time": f"{future}-06-01T10:00:00", "capture_time_kind": "local"},   # 9
        {"capture_time": "2025-01-15T10:00:00", "capture_time_kind": None},           # 10
        {"capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local",
         "deleted_at": "2025-02-01 00:00:00"},                                        # 11
    ]
    ids = await _insert_rows(db_path, rows)

    async def day(d, capture_tz="UTC"):
        got, count = await _capture_ids(db_path, _filters(capture_date=d), capture_tz)
        assert count == len(got)
        return got

    assert await day("2025-01-15", "UTC") == sorted([ids[0], ids[1], ids[6]])
    assert await day("2025-01-15", "Asia/Shanghai") == sorted([ids[0], ids[6]])
    assert await day("2025-01-16", "Asia/Shanghai") == [ids[1]]
    assert await day("2025-01-15", "America/New_York") == sorted([ids[0], ids[1], ids[6]])
    assert await day("1988-07-02", "Asia/Shanghai") == [ids[2]]   # DST: +09:00
    assert await day("1988-07-01", "Asia/Shanghai") == []
    assert await day("1800-06-01") == []
    assert await day(f"{future}-06-01") == []


async def test_capture_date_combined_with_other_filters(db_path):
    rows = [
        {"file_path": "/m/u/A/1.jpg", "file_name": "IMG_1.jpg", "device_name": "P",
         "capture_time": "2025-01-15T10:00:00", "capture_time_kind": "local"},
        {"file_path": "/m/u/A/sub/2.jpg", "file_name": "IMG_2.jpg", "device_name": "P",
         "capture_time": "2025-01-15T11:00:00", "capture_time_kind": "local"},
        {"file_path": "/m/u/B/3.jpg", "file_name": "IMG_3.jpg", "device_name": "P",
         "capture_time": "2025-01-15T12:00:00", "capture_time_kind": "local"},
        {"file_path": "/m/u/A/4.jpg", "file_name": "DSC_4.jpg", "device_name": "Q",
         "capture_time": "2025-01-15T13:00:00", "capture_time_kind": "local"},
        {"file_path": "/m/u/A/5.jpg", "file_name": "IMG_5.jpg", "device_name": "P",
         "capture_time": "2025-01-16T13:00:00", "capture_time_kind": "local"},
    ]
    ids = await _insert_rows(db_path, rows)
    d = "2025-01-15"
    cases = [
        (_filters(capture_date=d, dir_prefix="/m/u/A/"), [0, 1, 3]),
        (_filters(capture_date=d, dir_prefix="/m/u/A/", recursive=False), [0, 3]),
        (_filters(capture_date=d, dir_prefix="/m/u/A/", q="img"), [0, 1]),
        (_filters(capture_date=d, device=("Q",)), [3]),
        (_filters(capture_date=d, date=("2020-01-01", "2020-01-01")), [0, 1, 2, 3]),
        (_filters(capture_date=d, date=("2021-01-01", "2021-01-01")), []),
    ]
    for flt, expected in cases:
        got, _ = await _capture_ids(db_path, flt, "UTC")
        assert got == sorted(ids[i] for i in expected), flt


async def test_capture_date_requires_capture_table(db_path):
    await _insert_rows(db_path, [{"capture_time": "2025-01-15T10:00:00",
                                  "capture_time_kind": "utc"}])
    with pytest.raises(sqlite3.OperationalError, match="timeline_capture_tz"):
        await _run(db_path, "summary", 1, _filters(capture_date="2025-01-15"),
                   _intervals("UTC"))
    db = await _connect(db_path)
    try:
        svc = TimelineService(db)
        await svc.summary(1, _filters(capture_date="2025-01-15"), _intervals("UTC"),
                          capture_intervals=_intervals("UTC"))
        cursor = await db.execute(
            "SELECT name FROM sqlite_temp_master WHERE name LIKE 'timeline%'"
        )
        assert await cursor.fetchall() == []  # both temp tables dropped
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# Endpoints: path / recursive / capture_date
# ---------------------------------------------------------------------------


async def test_endpoint_path_and_capture_date(db_path, client):
    _, token = await _create_user(db_path, "alice")
    base = f"{get_settings().media_root}/alice"
    ids = await _insert_rows(db_path, [
        {"file_path": f"{base}/A/1.jpg", "file_name": "1.jpg", "exif_time": _T},
        {"file_path": f"{base}/A/sub/2.jpg", "file_name": "2.jpg", "exif_time": _T},
        {"file_path": f"{base}/B/3.jpg", "file_name": "3.jpg", "exif_time": _T},
        {"file_path": "/mnt/custom/4.jpg", "file_name": "4.jpg", "exif_time": _T},
    ])
    h = _auth(token)

    async def count(**params):
        r = await client.get(SUMMARY_URL, params={"tz": "UTC", **params}, headers=h)
        assert r.status_code == 200, r.text
        assert r.json()["total_count"] == 4
        return r.json()["filtered_count"]

    assert await count(path="A") == 2
    assert await count(path="/A/", recursive="false") == 1
    assert await count(path="A/sub") == 1
    assert await count(path="") == 4
    assert await count(path="", recursive="false") == 4   # root ignores recursive
    assert await count(path="/") == 4
    assert await count(path="Nope") == 0

    r = await client.get(FILES_URL, params={"tz": "UTC", "month": "2024-06", "path": "A",
                                            "recursive": "false"}, headers=h)
    assert r.status_code == 200
    assert [f["id"] for f in r.json()["files"]] == [ids[0]]


async def test_endpoint_capture_date_follows_config_tz(db_path, client):
    os.environ["PHOTOVAULT_DEFAULT_TIMEZONE"] = "UTC"
    reset_settings()
    _, token = await _create_user(db_path, "alice")
    # Time key: UTC created_at -> July in Shanghai; capture day by config (UTC).
    [row_id] = await _insert_rows(db_path, [
        {"exif_time": None, "created_at": "2024-06-30 17:00:00",
         "capture_time": "2024-06-30T17:00:00", "capture_time_kind": "utc"},
    ])
    h = _auth(token)
    params = {"tz": "Asia/Shanghai", "capture_date": "2024-06-30"}
    r = await client.get(SUMMARY_URL, params=params, headers=h)
    assert r.status_code == 200
    body = r.json()
    assert body["tz"] == "Asia/Shanghai"
    assert body["filtered_count"] == 1
    assert body["months"] == [{"year": 2024, "month": 7, "count": 1}]
    r = await client.get(FILES_URL, params={**params, "month": "2024-07"}, headers=h)
    assert [f["id"] for f in r.json()["files"]] == [row_id]
    r = await client.get(SUMMARY_URL, params={**params, "capture_date": "2024-07-01"},
                         headers=h)
    assert r.json()["filtered_count"] == 0


@pytest.mark.parametrize(
    ("params", "code"),
    [
        ({"path": "a/../b"}, "invalid_path"),
        ({"path": "a//b"}, "invalid_path"),
        ({"path": "a\\b"}, "invalid_path"),
        ({"path": "a\x00b"}, "invalid_path"),
        ({"path": "a" * 1025}, "invalid_path"),
        ({"path": "a" * 1101}, None),
        ({"recursive": "abc"}, None),
        ({"capture_date": "2024-1-1"}, None),
        ({"capture_date": "2024-02-30"}, None),
        ({"capture_date": "today"}, None),
    ],
)
@pytest.mark.parametrize("url", [SUMMARY_URL, FILES_URL])
async def test_endpoint_path_and_capture_date_validation(db_path, client, url, params, code):
    _, token = await _create_user(db_path, "alice")
    if url == FILES_URL:
        params = {**params, "month": "2024-06"}
    r = await client.get(url, params=params, headers=_auth(token))
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    if code is not None:
        assert detail["code"] == code and detail["message"]
    else:
        assert isinstance(detail, list)  # FastAPI request validation


# ---------------------------------------------------------------------------
# Cross-check: timeline capture_date counts == /stats/media overview.daily
# ---------------------------------------------------------------------------


@pytest.fixture
async def stats_client(db_path):
    from app.api.stats import router as stats_router

    app = _make_app(db_path)
    app.include_router(stats_router)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


_XC_TIMES = [
    ("2025-01-15T18:30:00", "utc"),
    ("2025-01-15T03:30:00", "utc"),
    ("2025-03-09T06:30:00", "utc"),     # New York DST start (07:00 UTC)
    ("2025-03-09T07:30:00", "utc"),
    ("1988-07-01T15:30:00", "utc"),     # Shanghai DST
    ("2024-12-31T23:30:00", "utc"),
    ("2025-01-15T23:30:00", "local"),
    ("2025-01-16T00:10:00", "local"),
    ("2024-12-31T23:59:59", "local"),
    ("2025-03-09T02:30:00", "local"),
    ("2025-01-15Tgarbage", "local"),    # invalid: unknown in the stats
    ("1800-06-01T10:00:00", "local"),   # out of range
    ("1800-06-01T10:00:00", "utc"),
    (None, None),
]
_XC_KINDS = [  # (file name suffix, media_type, mime_type)
    (".jpg", "image", "image/jpeg"),
    (".mp4", "video", "video/mp4"),
    (".txt", "image", "text/plain"),    # 'other': not in the heatmap
    (".bin", "image", "image/heic"),    # photo by mime type
    (".HEIC", "image", None),           # photo by extension
]


def _cross_check_rows(base: str) -> list[dict]:
    folders = [f"{base}/A", f"{base}/A/sub", f"{base}/A_x", f"{base}/B", "/mnt/custom/Cam"]
    rows = []
    n = 0
    for folder in folders:
        for i, (ct, kind) in enumerate(_XC_TIMES):
            for j, (suffix, media_type, mime) in enumerate(_XC_KINDS):
                n += 1
                if (i + j) % 3 == 1:
                    continue  # thin the grid so days get different counts
                name = f"F{n:04d}{suffix}"
                rows.append({
                    "file_path": f"{folder}/{name}", "file_name": name,
                    "media_type": media_type, "mime_type": mime,
                    "capture_time": ct, "capture_time_kind": kind,
                    "is_reference": 1 if n % 11 == 0 else 0,
                })
        # A '/.trash/' path row and a trashed row on a busy day.
        rows.append({"file_path": f"{folder}/.trash/T{n}.jpg", "file_name": f"T{n}.jpg",
                     "capture_time": "2025-01-15T23:30:00", "capture_time_kind": "local"})
        rows.append({"file_path": f"{folder}/D{n}.jpg", "file_name": f"D{n}.jpg",
                     "capture_time": "2025-01-15T23:30:00", "capture_time_kind": "local",
                     "deleted_at": "2025-02-01 00:00:00"})
    return rows


@pytest.mark.parametrize(
    ("tz", "config_tz"),
    [("Asia/Shanghai", None), ("America/New_York", None), ("UTC", None),
     ("Asia/Shanghai", "America/New_York")],
)
async def test_cross_check_capture_date_matches_stats_daily(
    db_path, stats_client, tz, config_tz
):
    if config_tz:
        os.environ["PHOTOVAULT_DEFAULT_TIMEZONE"] = config_tz
        reset_settings()
    alice, token = await _create_user(db_path, "alice")
    bob, _ = await _create_user(db_path, "bob")
    base = f"{get_settings().media_root}/alice"
    rows = _cross_check_rows(base)
    for r in rows:
        r["user_id"] = alice
    rows.append({"user_id": bob, "file_path": f"{get_settings().media_root}/bob/A/x.jpg",
                 "file_name": "x.jpg", "capture_time": "2025-01-15T23:30:00",
                 "capture_time_kind": "local"})
    await _insert_rows(db_path, rows)
    h = _auth(token)

    for path, recursive in [("", True), ("A", True), ("A", False), ("A/sub", True),
                            ("B", True), ("A_x", True)]:
        scope = {"tz": tz, "path": path, "recursive": "true" if recursive else "false"}
        r = await stats_client.get("/stats/media", params=scope, headers=h)
        assert r.status_code == 200, r.text
        daily = r.json()["overview"]["daily"]
        assert sum(daily["days"].values()) + daily["unknown"] == daily["total"]
        assert len(daily["days"]) >= 5 and daily["unknown"] > 0
        for day, expected in daily["days"].items():
            r = await stats_client.get(
                SUMMARY_URL, params={**scope, "capture_date": day}, headers=h
            )
            assert r.status_code == 200, r.text
            body = r.json()
            assert body["filtered_count"] == expected, (path, recursive, day)
            # The files pages list exactly those rows.
            listed = 0
            for m in body["months"]:
                r = await stats_client.get(FILES_URL, params={
                    **scope, "capture_date": day, "month": f"{m['year']:04d}-{m['month']:02d}",
                    "limit": 500,
                }, headers=h)
                assert r.status_code == 200 and r.json()["has_more"] is False
                listed += len(r.json()["files"])
            assert listed == expected
        for absent in ("2000-01-01", "1800-06-01", "2025-01-14"):
            if absent in daily["days"]:
                continue
            r = await stats_client.get(
                SUMMARY_URL, params={**scope, "capture_date": absent}, headers=h
            )
            assert r.json()["filtered_count"] == 0, (path, recursive, absent)
