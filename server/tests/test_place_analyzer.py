"""Unit tests for PlaceAnalyzer (GPS extraction, offline geocoding, persistence).

These tests cover the stateful analyzer behaviour of task 2.3:
- no-GPS photos produce no photo_gps record (Requirement 2.1);
- coordinates are reverse-geocoded offline against cities.db (Requirement 2.2);
- when cities.db is missing only raw lat/lon are stored (Requirement 2.5);
- the persisted user_id matches the file owner (Requirement 2.6);
- re-analysis replaces the prior row rather than accumulating (Requirement 7.5).

The Pillow EXIF read boundary is substituted in the geocoding/persistence tests
so they exercise the real reverse-geocoding + SQLite logic deterministically
without hand-crafting GPS EXIF bytes; GPS EXIF parsing itself is covered by the
parse_gps_ifd tests.
"""

import sqlite3
import types

import aiosqlite
import pytest
from PIL import Image

from app.core.database import init_db
from app.services import place_analyzer as pa
from app.services.place_analyzer import PlaceAnalyzer


def _make_settings(tmp_path):
    """Build a minimal settings-like object for the analyzer."""
    models_root = tmp_path / "models"
    db_path = tmp_path / "photovault.db"
    return types.SimpleNamespace(
        models_root=str(models_root),
        database_url=str(db_path),
    )


async def _setup_db(db_url: str) -> int:
    """Init schema and insert a user + file_record; return the file_id."""
    await init_db(db_url)
    db = await aiosqlite.connect(db_url)
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
        await db.execute(
            "INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'h')"
        )
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (10, 1, 'hash', '/f', '/o', 'dev', 100, 'a.jpg')"""
        )
        await db.commit()
    finally:
        await db.close()
    return 10


def _make_cities_db(path, rows):
    """Create a cities.db with a standard schema and given rows."""
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.execute(
            "CREATE TABLE cities (name TEXT, province TEXT, country TEXT, "
            "latitude REAL, longitude REAL)"
        )
        conn.executemany(
            "INSERT INTO cities VALUES (?, ?, ?, ?, ?)", rows
        )
        conn.commit()
    finally:
        conn.close()


async def _fetch_gps(db_url: str, file_id: int):
    db = await aiosqlite.connect(db_url)
    db.row_factory = aiosqlite.Row
    try:
        cursor = await db.execute(
            "SELECT * FROM photo_gps WHERE file_id = ?", (file_id,)
        )
        return await cursor.fetchall()
    finally:
        await db.close()


async def test_no_gps_writes_no_record(tmp_path):
    """A photo without GPS EXIF must not create a photo_gps row."""
    settings = _make_settings(tmp_path)
    file_id = await _setup_db(settings.database_url)

    img_path = tmp_path / "plain.jpg"
    Image.new("RGB", (8, 8), (10, 20, 30)).save(img_path)

    analyzer = PlaceAnalyzer(settings=settings)
    await analyzer.analyze(user_id=1, file_id=file_id, path=str(img_path))

    rows = await _fetch_gps(settings.database_url, file_id)
    assert rows == []


async def test_available_reflects_cities_db_presence(tmp_path):
    settings = _make_settings(tmp_path)
    analyzer = PlaceAnalyzer(settings=settings)
    assert analyzer.available is False

    _make_cities_db(analyzer.cities_db_path, [("Paris", "IDF", "FR", 48.85, 2.35)])
    assert analyzer.available is True


async def test_reverse_geocodes_and_persists(tmp_path, monkeypatch):
    """With cities.db present, coordinates resolve to the nearest city."""
    settings = _make_settings(tmp_path)
    file_id = await _setup_db(settings.database_url)
    analyzer = PlaceAnalyzer(settings=settings)
    _make_cities_db(
        analyzer.cities_db_path,
        [
            ("Paris", "IDF", "FR", 48.8566, 2.3522),
            ("Berlin", "BE", "DE", 52.52, 13.405),
        ],
    )

    # Substitute the Pillow EXIF read boundary with a known coordinate near Paris.
    monkeypatch.setattr(pa, "_extract_gps_coords", lambda path: (48.86, 2.35))

    img_path = tmp_path / "geo.jpg"
    Image.new("RGB", (8, 8)).save(img_path)

    await analyzer.analyze(user_id=1, file_id=file_id, path=str(img_path))

    rows = await _fetch_gps(settings.database_url, file_id)
    assert len(rows) == 1
    row = rows[0]
    assert row["user_id"] == 1
    assert row["city"] == "Paris"
    assert row["province"] == "IDF"
    assert row["country"] == "FR"
    assert row["geocoded_at"] is not None
    assert abs(row["latitude"] - 48.86) < 1e-6
    assert abs(row["longitude"] - 2.35) < 1e-6


async def test_stores_coords_without_city_when_db_missing(tmp_path, monkeypatch):
    """Without cities.db, raw lat/lon are stored and city fields stay null."""
    settings = _make_settings(tmp_path)
    file_id = await _setup_db(settings.database_url)
    analyzer = PlaceAnalyzer(settings=settings)
    assert analyzer.available is False

    monkeypatch.setattr(pa, "_extract_gps_coords", lambda path: (10.0, 20.0))
    img_path = tmp_path / "geo.jpg"
    Image.new("RGB", (8, 8)).save(img_path)

    await analyzer.analyze(user_id=1, file_id=file_id, path=str(img_path))

    rows = await _fetch_gps(settings.database_url, file_id)
    assert len(rows) == 1
    row = rows[0]
    assert row["city"] is None
    assert row["province"] is None
    assert row["country"] is None
    assert row["geocoded_at"] is None
    assert abs(row["latitude"] - 10.0) < 1e-6
    assert abs(row["longitude"] - 20.0) < 1e-6


async def test_reanalysis_is_idempotent(tmp_path, monkeypatch):
    """Re-analyzing the same file replaces the row rather than duplicating it."""
    settings = _make_settings(tmp_path)
    file_id = await _setup_db(settings.database_url)
    analyzer = PlaceAnalyzer(settings=settings)
    _make_cities_db(analyzer.cities_db_path, [("Paris", "IDF", "FR", 48.86, 2.35)])

    monkeypatch.setattr(pa, "_extract_gps_coords", lambda path: (48.86, 2.35))
    img_path = tmp_path / "geo.jpg"
    Image.new("RGB", (8, 8)).save(img_path)

    await analyzer.analyze(user_id=1, file_id=file_id, path=str(img_path))
    await analyzer.analyze(user_id=1, file_id=file_id, path=str(img_path))

    rows = await _fetch_gps(settings.database_url, file_id)
    assert len(rows) == 1
