"""Place analysis: GPS EXIF parsing and offline reverse geocoding helpers.

This module hosts the *pure* logic used by the place-analysis pipeline so it can
be unit- and property-tested without any IO, Pillow image decoding, or database
access:

- :func:`parse_gps_ifd` converts a Pillow GPS IFD mapping (the sub-dictionary
  returned by ``Image.getexif().get_ifd(IFD.GPSInfo)``) into decimal
  ``(latitude, longitude)`` degrees, honouring the N/S/E/W reference directions.
- :func:`nearest_city` finds the closest :class:`CityRecord` to a coordinate
  from an in-memory list of candidate cities.

The stateful :class:`PlaceAnalyzer` (Pillow decoding, ``cities.db`` loading and
``photo_gps`` persistence) is added in a later task and builds on top of these
functions.

GPS IFD tag reference (EXIF spec):

- tag 1  ``GPSLatitudeRef``   -> ``'N'`` / ``'S'``
- tag 2  ``GPSLatitude``      -> 3 rationals (degrees, minutes, seconds)
- tag 3  ``GPSLongitudeRef``  -> ``'E'`` / ``'W'``
- tag 4  ``GPSLongitude``     -> 3 rationals (degrees, minutes, seconds)
"""

from __future__ import annotations

import logging
import math
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, NamedTuple, Optional

logger = logging.getLogger("photovault.place_analyzer")

# GPS IFD tag numbers (see EXIF GPSInfo IFD).
_GPS_LATITUDE_REF = 1
_GPS_LATITUDE = 2
_GPS_LONGITUDE_REF = 3
_GPS_LONGITUDE = 4

# EXIF root-IFD tag pointing at the GPS sub-IFD.
_GPS_INFO_IFD_TAG = 34853


class CityRecord(NamedTuple):
    """A single reverse-geocoding candidate city.

    Coordinates are in decimal degrees. ``province`` and ``country`` are
    optional descriptive fields; only ``name`` and the coordinates are required
    for nearest-neighbour matching.

    ``name`` holds the primary (romanised/English) city name and ``name_zh``
    the Chinese form when the geocoding dataset provides one. Both are carried
    through the pipeline so the Web端 can display either — bilingual display is
    a reserved capability, not required for matching.
    """

    name: str
    province: Optional[str]
    country: Optional[str]
    latitude: float
    longitude: float
    name_zh: Optional[str] = None


def _to_float(value: Any) -> Optional[float]:
    """Best-effort convert an EXIF rational-ish value to ``float``.

    Handles the shapes Pillow (and raw EXIF) may yield:

    - ``PIL.TiffImagePlugin.IFDRational`` (already a ``Rational`` / floatable)
    - a ``(numerator, denominator)`` pair as a tuple/list
    - a plain ``int`` / ``float``

    Returns ``None`` when the value cannot be interpreted or is non-finite
    (e.g. a zero-denominator rational).
    """
    if value is None:
        return None

    # (numerator, denominator) pair.
    if isinstance(value, (tuple, list)):
        if len(value) != 2:
            return None
        num, den = value
        try:
            num_f = float(num)
            den_f = float(den)
        except (TypeError, ValueError):
            return None
        if den_f == 0:
            return None
        result = num_f / den_f
        return result if math.isfinite(result) else None

    # IFDRational or plain number: both are floatable. IFDRational with a zero
    # denominator yields nan/inf, which we reject below.
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _dms_to_degrees(dms: Any) -> Optional[float]:
    """Convert a (degrees, minutes, seconds) rational triple to decimal degrees.

    Returns ``None`` if the triple is missing, malformed, or contains a
    component that cannot be converted to a finite number.
    """
    if not isinstance(dms, (tuple, list)) or len(dms) != 3:
        return None

    degrees = _to_float(dms[0])
    minutes = _to_float(dms[1])
    seconds = _to_float(dms[2])
    if degrees is None or minutes is None or seconds is None:
        return None

    return degrees + minutes / 60.0 + seconds / 3600.0


def parse_gps_ifd(gps_ifd: Any) -> Optional[tuple[float, float]]:
    """Parse a Pillow GPS IFD mapping into ``(latitude, longitude)`` degrees.

    Args:
        gps_ifd: The GPS sub-IFD, typically ``Image.getexif().get_ifd(
            IFD.GPSInfo)`` — a mapping of GPS tag number to value.

    Returns:
        ``(latitude, longitude)`` in decimal degrees where ``S``/``W``
        references produce negative values and ``N``/``E`` produce positive
        values, or ``None`` when any required component (coordinate triple or
        reference direction) is missing, malformed, or out of range.
    """
    if not isinstance(gps_ifd, dict) or not gps_ifd:
        return None

    lat_ref = gps_ifd.get(_GPS_LATITUDE_REF)
    lon_ref = gps_ifd.get(_GPS_LONGITUDE_REF)
    lat_dms = gps_ifd.get(_GPS_LATITUDE)
    lon_dms = gps_ifd.get(_GPS_LONGITUDE)

    if lat_ref is None or lon_ref is None or lat_dms is None or lon_dms is None:
        return None

    # Normalise reference directions to a single upper-case character.
    lat_ref = str(lat_ref).strip().upper()[:1]
    lon_ref = str(lon_ref).strip().upper()[:1]
    if lat_ref not in ("N", "S") or lon_ref not in ("E", "W"):
        return None

    latitude = _dms_to_degrees(lat_dms)
    longitude = _dms_to_degrees(lon_dms)
    if latitude is None or longitude is None:
        return None

    if lat_ref == "S":
        latitude = -latitude
    if lon_ref == "W":
        longitude = -longitude

    # Reject coordinates outside the valid geographic range.
    if not (-90.0 <= latitude <= 90.0) or not (-180.0 <= longitude <= 180.0):
        return None

    return latitude, longitude


def _squared_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Approximate squared great-circle distance between two coordinates.

    Uses an equirectangular projection (scaling longitude by ``cos(latitude)``)
    which is cheap and monotonic with true distance for nearest-neighbour
    selection over the modest spans between a photo and candidate cities.
    """
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    dlat = lat1 - lat2
    dlon = (lon1 - lon2) * math.cos(mean_lat)
    return dlat * dlat + dlon * dlon


def nearest_city(
    lat: float, lon: float, cities: list[CityRecord]
) -> Optional[CityRecord]:
    """Return the city in ``cities`` closest to ``(lat, lon)``.

    Args:
        lat: Query latitude in decimal degrees.
        lon: Query longitude in decimal degrees.
        cities: Candidate cities to search.

    Returns:
        The nearest :class:`CityRecord`, or ``None`` when ``cities`` is empty.
    """
    if not cities:
        return None

    return min(
        cities,
        key=lambda c: _squared_distance(lat, lon, c.latitude, c.longitude),
    )


# ---------------------------------------------------------------------------
# Reverse-geocoding database column detection
# ---------------------------------------------------------------------------

# Candidate column names for each CityRecord field, tried in order. This keeps
# the loader tolerant of the different schemas an offline city dataset (e.g. a
# GeoNames export) might use, since the resource file is user-supplied.
_NAME_COLUMNS = ("name", "city", "city_name", "asciiname", "town")
_NAME_ZH_COLUMNS = ("name_zh", "name_cn", "zh_name", "cn_name", "local_name")
_PROVINCE_COLUMNS = ("province", "admin1", "admin1_name", "state", "region")
_COUNTRY_COLUMNS = ("country", "country_name", "country_code", "cc")
_LAT_COLUMNS = ("latitude", "lat")
_LON_COLUMNS = ("longitude", "lon", "lng", "long")


def _pick_column(available: set[str], candidates: tuple[str, ...]) -> Optional[str]:
    """Return the first candidate column present in ``available`` (case-insensitive)."""
    lookup = {col.lower(): col for col in available}
    for candidate in candidates:
        if candidate in lookup:
            return lookup[candidate]
    return None


def _load_cities_from_db(db_path: Path) -> list[CityRecord]:
    """Load all city records from an offline reverse-geocoding SQLite database.

    The function is defensive about the concrete schema: it locates a table that
    exposes name + latitude + longitude columns (checking a ``cities`` table
    first, then any other user table) and maps common column-name variants onto
    :class:`CityRecord` fields. Rows with unparseable coordinates are skipped.

    Any failure to read the database yields an empty list (logged), so a
    corrupt or unexpected resource file degrades gracefully rather than raising.
    """
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    except sqlite3.Error as exc:  # pragma: no cover - defensive
        logger.warning("Could not open geocoding database %s: %s", db_path, exc)
        return []

    try:
        conn.row_factory = sqlite3.Row
        cursor = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
        table_names = [row[0] for row in cursor.fetchall()]
        if not table_names:
            return []

        # Prefer a table literally named 'cities', else search every table for
        # one that has usable name/lat/lon columns.
        ordered = sorted(table_names, key=lambda t: (t.lower() != "cities", t))

        for table in ordered:
            info = conn.execute(f'PRAGMA table_info("{table}")').fetchall()
            columns = {row[1] for row in info}
            name_col = _pick_column(columns, _NAME_COLUMNS)
            lat_col = _pick_column(columns, _LAT_COLUMNS)
            lon_col = _pick_column(columns, _LON_COLUMNS)
            if not (name_col and lat_col and lon_col):
                continue

            province_col = _pick_column(columns, _PROVINCE_COLUMNS)
            country_col = _pick_column(columns, _COUNTRY_COLUMNS)
            name_zh_col = _pick_column(columns, _NAME_ZH_COLUMNS)

            select_cols = [f'"{name_col}"', f'"{lat_col}"', f'"{lon_col}"']
            select_cols.append(f'"{province_col}"' if province_col else "NULL")
            select_cols.append(f'"{country_col}"' if country_col else "NULL")
            # Optional Chinese-name column — NULL when the dataset lacks it, so
            # legacy single-name databases keep working (name_zh stays None).
            select_cols.append(f'"{name_zh_col}"' if name_zh_col else "NULL")
            query = f'SELECT {", ".join(select_cols)} FROM "{table}"'

            cities: list[CityRecord] = []
            for row in conn.execute(query):
                name = row[0]
                lat = _to_float(row[1])
                lon = _to_float(row[2])
                if name is None or lat is None or lon is None:
                    continue
                province = row[3] if row[3] is not None else None
                country = row[4] if row[4] is not None else None
                name_zh = row[5] if row[5] is not None else None
                cities.append(
                    CityRecord(
                        name=str(name),
                        province=str(province) if province is not None else None,
                        country=str(country) if country is not None else None,
                        latitude=lat,
                        longitude=lon,
                        name_zh=str(name_zh) if name_zh is not None else None,
                    )
                )
            return cities

        logger.warning(
            "No usable city table (name/latitude/longitude) found in %s", db_path
        )
        return []
    except sqlite3.Error as exc:
        logger.warning("Failed reading geocoding database %s: %s", db_path, exc)
        return []
    finally:
        conn.close()


def _extract_gps_coords(path: str) -> Optional[tuple[float, float]]:
    """Read the GPS IFD from an image via Pillow and parse it to coordinates.

    Returns ``(latitude, longitude)`` in decimal degrees, or ``None`` when the
    file cannot be opened, has no EXIF/GPS data, or the GPS data is malformed.
    """
    try:
        from app.services.image_utils import open_image

        with open_image(path) as img:
            exif = img.getexif()
            if not exif:
                return None

            gps_ifd: Any = None
            # Preferred: the dedicated GPS sub-IFD accessor.
            try:
                gps_ifd = exif.get_ifd(_GPS_INFO_IFD_TAG)
            except Exception:
                gps_ifd = None
            # Fallback: some files expose the GPS IFD directly on the root IFD.
            if not gps_ifd:
                gps_ifd = exif.get(_GPS_INFO_IFD_TAG)

            if not gps_ifd:
                return None

            return parse_gps_ifd(dict(gps_ifd))
    except Exception as exc:
        logger.debug("Could not extract GPS from %s: %s", path, exc)
        return None


class PlaceAnalyzer:
    """Extract photo GPS location and reverse-geocode it offline.

    For each analyzed photo the analyzer:

    1. reads the GPS coordinates from EXIF (via Pillow);
    2. if the offline :class:`GeocodingDB` (``geocoding/cities.db``) is
       installed, reverse-geocodes the coordinates to a city/province/country
       using a purely local nearest-neighbour lookup (no network access);
    3. writes a single ``photo_gps`` row for the file, clearing any prior row
       first so repeated analysis stays idempotent.

    Photos without GPS data produce no record. When the geocoding database is
    not installed the raw latitude/longitude are still stored, just without the
    city fields.
    """

    def __init__(self, settings: Any = None) -> None:
        """Create the analyzer.

        Args:
            settings: Optional settings object exposing ``models_root`` and
                ``database_url``. Defaults to the application settings
                singleton. Injectable for testing.
        """
        if settings is None:
            from app.core.config import get_settings

            settings = get_settings()
        self._settings = settings
        # Cache of loaded cities keyed by the resource file's mtime so an
        # updated cities.db (uploaded at runtime) is reloaded on next use.
        self._cities_cache: Optional[list[CityRecord]] = None
        self._cities_mtime: Optional[float] = None

    @property
    def cities_db_path(self) -> Path:
        """Filesystem path to the offline geocoding database."""
        return Path(self._settings.models_root) / "geocoding" / "cities.db"

    @property
    def available(self) -> bool:
        """Whether the offline geocoding database is installed and readable."""
        return self.cities_db_path.is_file()

    def _get_cities(self) -> list[CityRecord]:
        """Return the loaded city list, (re)loading it if the file changed."""
        db_path = self.cities_db_path
        try:
            mtime = db_path.stat().st_mtime
        except OSError:
            return []

        if self._cities_cache is None or self._cities_mtime != mtime:
            self._cities_cache = _load_cities_from_db(db_path)
            self._cities_mtime = mtime
        return self._cities_cache

    async def analyze(self, user_id: int, file_id: int, path: str) -> None:
        """Analyze a single photo's location and persist it to ``photo_gps``.

        Args:
            user_id: Owner of the file (persisted for user isolation).
            file_id: ``file_records.id`` of the photo being analyzed.
            path: Absolute filesystem path to the decoded image.

        No record is written when the photo has no usable GPS information.
        Existing rows for ``file_id`` are removed before insert so re-analysis
        does not accumulate duplicates.
        """
        coords = _extract_gps_coords(path)
        if coords is None:
            # Requirement 2.1: no GPS -> skip, produce no PhotoGPS record.
            return

        latitude, longitude = coords

        city: Optional[str] = None
        city_zh: Optional[str] = None
        province: Optional[str] = None
        country: Optional[str] = None
        geocoded_at: Optional[str] = None

        # Requirement 2.2 / 2.5: reverse-geocode offline when the database is
        # available; otherwise keep only the raw coordinates.
        if self.available:
            cities = self._get_cities()
            match = nearest_city(latitude, longitude, cities) if cities else None
            if match is not None:
                city = match.name
                city_zh = match.name_zh
                province = match.province
                country = match.country
                geocoded_at = datetime.now(timezone.utc).isoformat()

        await self._write_gps(
            user_id=user_id,
            file_id=file_id,
            latitude=latitude,
            longitude=longitude,
            city=city,
            city_zh=city_zh,
            province=province,
            country=country,
            geocoded_at=geocoded_at,
        )

    async def _write_gps(
        self,
        *,
        user_id: int,
        file_id: int,
        latitude: float,
        longitude: float,
        city: Optional[str],
        city_zh: Optional[str],
        province: Optional[str],
        country: Optional[str],
        geocoded_at: Optional[str],
    ) -> None:
        """Persist a photo_gps row, replacing any existing row for the file.

        Opens its own aiosqlite connection (worker-isolated) so the analyzer can
        be driven from a background task without sharing a request connection.
        Both ``city`` (primary/romanised) and ``city_zh`` (Chinese, may be
        ``None``) are stored so the Web端 can display either.
        """
        from app.core.database import _create_connection

        db = await _create_connection(self._settings.database_url)
        try:
            # Requirement 7.5: clear prior result so re-analysis is idempotent.
            await db.execute("DELETE FROM photo_gps WHERE file_id = ?", (file_id,))
            await db.execute(
                """
                INSERT INTO photo_gps (
                    file_id, user_id, latitude, longitude,
                    city, city_zh, province, country, geocoded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    file_id,
                    user_id,
                    latitude,
                    longitude,
                    city,
                    city_zh,
                    province,
                    country,
                    geocoded_at,
                ),
            )
            await db.commit()
        finally:
            await db.close()
