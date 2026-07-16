"""Download and install AnalysisResource files from a URL.

Backs the「分析资源管理」page's one-click download/update feature. An admin
supplies a URL (with a sensible per-type default) and the server fetches the
file, then installs it into ``{models_root}/{type-dir}/`` so the next analysis
picks it up (no restart — analyzers reload by mtime).

Three fetch shapes are supported so the common open-source distributions work
out of the box:

- **Direct file** (``.onnx`` / ``.json`` / ``.db``) — validated against the
  per-type allow-list and moved into place (normalised names for scene/geocoding).
- **Zip archive** (``.zip``) — extracted; the relevant members are installed
  (e.g. InsightFace ``buffalo_l.zip`` → all ``*.onnx`` into ``faces/``).
- **GeoNames text dump** (``.txt`` or a ``.zip`` containing one) — converted
  into a ``cities.db`` SQLite database matching the reverse-geocoder loader's
  schema.

Security note: this performs a server-side fetch of an admin-supplied URL
(a limited SSRF surface). It is gated to admins on a self-hosted deployment,
restricted to ``http``/``https``, size-capped and timeout-bounded. The download
runs in a worker thread so the event loop is never blocked.
"""

from __future__ import annotations

import logging
import os
import shutil
import sqlite3
import ssl
import tempfile
import threading
import urllib.request
import zipfile
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import unquote, urlparse

logger = logging.getLogger("photovault.resource_downloader")

# ---------------------------------------------------------------------------
# Progress registry
# ---------------------------------------------------------------------------
#
# A single in-flight download per resource type is tracked here so the Web UI
# can poll progress while the server-side fetch runs in a worker thread. The
# registry is guarded by a lock because it is written from the download thread
# and read from the event loop.

_PROGRESS_LOCK = threading.Lock()
_PROGRESS: dict[str, dict[str, Any]] = {}


def _idle_progress() -> dict[str, Any]:
    return {
        "status": "idle",       # idle | running | success | error
        "phase": "",            # downloading | installing | ""
        "downloaded": 0,
        "total": None,
        "message": "",
        "error": None,
        "resource": None,
    }


def get_progress(rtype: str) -> dict[str, Any]:
    """Return a snapshot of the current progress for ``rtype``."""
    with _PROGRESS_LOCK:
        return dict(_PROGRESS.get(rtype, _idle_progress()))


def begin_progress(rtype: str) -> None:
    """Mark a download as started (running / downloading)."""
    with _PROGRESS_LOCK:
        _PROGRESS[rtype] = {
            **_idle_progress(),
            "status": "running",
            "phase": "downloading",
        }


def _update_progress(rtype: str, **fields: Any) -> None:
    with _PROGRESS_LOCK:
        current = _PROGRESS.get(rtype)
        if current is None:
            current = _idle_progress()
            _PROGRESS[rtype] = current
        current.update(fields)


def mark_success(rtype: str, message: str, resource: Any = None) -> None:
    """Record a completed download."""
    _update_progress(
        rtype,
        status="success",
        phase="",
        message=message,
        error=None,
        resource=resource,
    )


def mark_error(rtype: str, message: str) -> None:
    """Record a failed download."""
    _update_progress(rtype, status="error", phase="", error=message)


def is_running(rtype: str) -> bool:
    return get_progress(rtype).get("status") == "running"

# Map the public resource ``type`` to its sub-directory under models_root.
RESOURCE_DIRS: dict[str, str] = {
    "face": "faces",
    "scene": "scenes",
    "geocoding": "geocoding",
}

# Accepted (non-archive) file extensions per resource type.
ALLOWED_EXTENSIONS: dict[str, set[str]] = {
    "face": {".onnx"},
    "scene": {".onnx", ".json"},
    "geocoding": {".db"},
}

# Hard limits for the server-side fetch.
_MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024 * 1024  # 2 GB
_DOWNLOAD_TIMEOUT = 60  # seconds for the initial connection/response
_CHUNK = 1024 * 1024

# GeoNames "geoname" table column indices (see download.geonames.org readme).
_GN_NAME = 1
_GN_ALTERNATENAMES = 3
_GN_LATITUDE = 4
_GN_LONGITUDE = 5
_GN_FEATURE_CLASS = 6
_GN_FEATURE_CODE = 7
_GN_COUNTRY = 8
_GN_ADMIN1 = 10
_GN_POPULATION = 14

# City-level granularity filter for the GeoNames conversion.
#
# GeoNames populated places (feature class ``P``) range from country capitals
# down to individual city sections / hamlets. Keeping every row makes the
# nearest-neighbour lookup resolve to street/subdistrict names (e.g. a
# ``PPLX`` city section or a ``PPLA4`` township seat) instead of a city. We
# therefore keep only genuinely city-level features:
#
#   PPLC  - national capital
#   PPLA  - seat of a first-order admin division (province capital)
#   PPLA2 - seat of a second-order admin division (prefecture-level city)
#   PPLA3 - seat of a third-order admin division (county-level city / town)
#
# Explicitly excluded: PPLA4 / PPLA5 (township / subdistrict seats), PPLX
# (section of a populated place), and generic small places. A generic ``PPL``
# is only kept when its population clears ``_MIN_CITY_POPULATION`` so that large
# cities GeoNames happens to code as plain ``PPL`` are not dropped.
_CITY_FEATURE_CODES = frozenset({"PPLC", "PPLA", "PPLA2", "PPLA3"})
_MIN_CITY_POPULATION = 15000


def _has_han(text: str) -> bool:
    """Return whether ``text`` contains a Han (Chinese) ideograph.

    Covers the common CJK Unified Ideographs block and Extension A, plus the
    compatibility-ideographs block — enough to recognise a Chinese place name.
    """
    for ch in text:
        code = ord(ch)
        if (
            0x4E00 <= code <= 0x9FFF
            or 0x3400 <= code <= 0x4DBF
            or 0xF900 <= code <= 0xFAFF
        ):
            return True
    return False


def _extract_chinese_name(name: str, alternatenames: str) -> Optional[str]:
    """Extract the Chinese (Han-script) form of a GeoNames place, if any.

    GeoNames stores the primary ``name`` of most Chinese cities romanised
    (e.g. ``"Beijing"``); the Chinese form lives among the comma-separated
    ``alternatenames`` (e.g. ``"北京市,北京,Beijing,..."``). This pure helper:

    - returns ``name`` itself when it already contains Han characters;
    - otherwise returns the first ``alternatenames`` entry that contains Han
      characters (the local/Chinese form when present);
    - otherwise returns ``None`` (no Chinese form available).

    The English/romanised ``name`` is preserved separately by the caller, so
    both forms are stored — this only surfaces the Chinese one.

    Note: the alternate-names list is not language-tagged, so this prefers any
    Han-script alias. For East Asian places that reliably yields the Chinese
    name; a language-tagged ``alternateNames`` file would be needed for full
    precision.
    """
    if _has_han(name):
        return name
    if alternatenames:
        for token in alternatenames.split(","):
            candidate = token.strip()
            if candidate and _has_han(candidate):
                return candidate
    return None


def _is_city_level(parts: list[str]) -> bool:
    """Return whether a GeoNames row is city-level (vs street/subdistrict).

    The decision is a pure function of the tab-split GeoNames columns so it can
    be unit-tested without any IO:

    - non populated-place rows (feature class other than ``P``) are rejected;
    - admin-seat feature codes in :data:`_CITY_FEATURE_CODES` are accepted;
    - any other populated place is accepted only when its population reaches
      :data:`_MIN_CITY_POPULATION`.

    When the feature-class / feature-code columns are absent (a trimmed dump),
    the row is accepted so such dumps still produce a usable database.
    """
    feature_class = (
        parts[_GN_FEATURE_CLASS].strip() if len(parts) > _GN_FEATURE_CLASS else ""
    )
    feature_code = (
        parts[_GN_FEATURE_CODE].strip() if len(parts) > _GN_FEATURE_CODE else ""
    )

    # Trimmed dump without classification columns: cannot filter, keep the row.
    if not feature_class and not feature_code:
        return True

    # Only populated places are candidate "cities".
    if feature_class and feature_class != "P":
        return False

    if feature_code in _CITY_FEATURE_CODES:
        return True

    # The population fallback only rescues *generic* populated places (or rows
    # with no feature code). Explicit sub-city codes (PPLA4/PPLA5 township &
    # subdistrict seats, PPLX city sections, ...) are always dropped even when
    # populous, since a Chinese 街道 can hold hundreds of thousands of people.
    if feature_code not in ("", "PPL"):
        return False

    population = 0
    if len(parts) > _GN_POPULATION:
        try:
            population = int(parts[_GN_POPULATION])
        except ValueError:
            population = 0
    return population >= _MIN_CITY_POPULATION


class ResourceDownloadError(Exception):
    """Raised when a resource download or install fails (readable message)."""


def _build_ssl_context() -> ssl.SSLContext:
    """Build a verifying SSL context backed by a usable CA bundle.

    Python installs (notably on macOS) frequently lack a system CA bundle,
    which surfaces as ``CERTIFICATE_VERIFY_FAILED``. Prefer the ``certifi``
    bundle when available so HTTPS verification works out of the box; fall back
    to the system default context otherwise. Verification is never disabled.
    """
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except Exception:  # pragma: no cover - certifi missing/unreadable
        return ssl.create_default_context()


def _basename_from_url(url: str) -> str:
    """Derive a filename from the URL path (unquoted)."""
    path = urlparse(url).path
    return os.path.basename(unquote(path)) or ""


def _sanitize_name(name: str) -> str:
    """Reduce an arbitrary name to a safe basename (no traversal)."""
    base = os.path.basename(name.replace("\\", "/"))
    if base in ("", ".", ".."):
        raise ResourceDownloadError("无法从下载内容确定合法文件名")
    return base


def _fetch_to_tmp(
    url: str,
    dest_dir: Path,
    progress: Optional[Callable[[int, Optional[int]], None]] = None,
) -> tuple[Path, str]:
    """Stream ``url`` into a temp file inside ``dest_dir``.

    Returns ``(tmp_path, source_name)`` where ``source_name`` is the best-effort
    original filename (used to determine how to install the download).

    ``progress`` is invoked as ``progress(downloaded_bytes, total_bytes_or_None)``
    after each chunk so callers can surface a progress bar.
    """
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ResourceDownloadError("仅支持 http/https 链接")

    req = urllib.request.Request(url, headers={"User-Agent": "PhotoVault"})
    context = _build_ssl_context() if parsed.scheme == "https" else None
    tmp_fd, tmp_name = tempfile.mkstemp(dir=str(dest_dir), suffix=".download")
    tmp_path = Path(tmp_name)
    try:
        with urllib.request.urlopen(  # noqa: S310 - scheme checked above
            req, timeout=_DOWNLOAD_TIMEOUT, context=context
        ) as resp:
            source_name = ""
            disp = resp.headers.get("Content-Disposition", "")
            if "filename=" in disp:
                source_name = disp.split("filename=", 1)[1].strip().strip('"; ')
            if not source_name:
                source_name = _basename_from_url(url)

            total: Optional[int] = None
            length = resp.headers.get("Content-Length")
            if length and length.isdigit():
                total = int(length)
            if progress is not None:
                progress(0, total)

            written = 0
            with os.fdopen(tmp_fd, "wb") as out:
                while True:
                    chunk = resp.read(_CHUNK)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > _MAX_DOWNLOAD_BYTES:
                        raise ResourceDownloadError("文件超过大小上限 (2GB)")
                    out.write(chunk)
                    if progress is not None:
                        progress(written, total)
        if written == 0:
            raise ResourceDownloadError("下载内容为空")
        return tmp_path, (source_name or "download")
    except ResourceDownloadError:
        _safe_unlink(tmp_path)
        raise
    except Exception as exc:  # noqa: BLE001 - surface a readable message
        _safe_unlink(tmp_path)
        raise ResourceDownloadError(f"下载失败: {exc}") from exc


def _safe_unlink(path: Path) -> None:
    try:
        path.unlink()
    except OSError:  # pragma: no cover - defensive
        pass


def _atomic_place(src: Path, dest: Path) -> None:
    """Move ``src`` onto ``dest`` atomically (same filesystem)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    os.replace(str(src), str(dest))


def _convert_geonames_to_db(txt_path: Path, db_path: Path) -> int:
    """Convert a GeoNames "geoname" tab-separated dump into a ``cities.db``.

    Builds a ``cities(name, province, country, latitude, longitude)`` table
    matching the reverse-geocoder loader. Only city-level rows are kept (see
    :func:`_is_city_level`) so reverse geocoding resolves to a city instead of a
    street/subdistrict. Rows with unparseable coordinates are skipped. Returns
    the number of inserted rows.
    """
    tmp_db = db_path.with_suffix(".db.tmp")
    _safe_unlink(tmp_db)

    conn = sqlite3.connect(str(tmp_db))
    inserted = 0
    try:
        # ``name`` keeps the romanised/English primary name; ``name_zh`` holds
        # the Chinese form (nullable) so the Web端 can show either language.
        conn.execute(
            "CREATE TABLE cities (name TEXT, name_zh TEXT, province TEXT, "
            "country TEXT, latitude REAL, longitude REAL)"
        )
        batch: list[tuple] = []
        with open(txt_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) <= _GN_LONGITUDE:
                    continue
                # Keep only city-level places so reverse geocoding resolves to a
                # city rather than a street/subdistrict (e.g. 清河 / 张八沟).
                if not _is_city_level(parts):
                    continue
                try:
                    lat = float(parts[_GN_LATITUDE])
                    lon = float(parts[_GN_LONGITUDE])
                except ValueError:
                    continue
                name = parts[_GN_NAME].strip()
                if not name:
                    continue
                # Keep the English/romanised name and, separately, derive the
                # Chinese form from the alternate names (e.g. "Beijing" plus
                # "北京市"). Both are stored for bilingual Web端 display.
                alternatenames = (
                    parts[_GN_ALTERNATENAMES]
                    if len(parts) > _GN_ALTERNATENAMES
                    else ""
                )
                name_zh = _extract_chinese_name(name, alternatenames)
                province = parts[_GN_ADMIN1].strip() if len(parts) > _GN_ADMIN1 else ""
                country = parts[_GN_COUNTRY].strip() if len(parts) > _GN_COUNTRY else ""
                batch.append(
                    (name, name_zh, province or None, country or None, lat, lon)
                )
                if len(batch) >= 5000:
                    conn.executemany(
                        "INSERT INTO cities VALUES (?, ?, ?, ?, ?, ?)", batch
                    )
                    inserted += len(batch)
                    batch.clear()
        if batch:
            conn.executemany("INSERT INTO cities VALUES (?, ?, ?, ?, ?, ?)", batch)
            inserted += len(batch)
        conn.execute(
            "CREATE INDEX idx_cities_latlon ON cities(latitude, longitude)"
        )
        conn.commit()
    finally:
        conn.close()

    if inserted == 0:
        _safe_unlink(tmp_db)
        raise ResourceDownloadError("GeoNames 数据为空或格式不符，未生成城市库")

    _atomic_place(tmp_db, db_path)
    return inserted


def _install_single_file(rtype: str, tmp_path: Path, source_name: str, target_dir: Path) -> str:
    """Install a single downloaded file according to the resource type."""
    ext = os.path.splitext(source_name)[1].lower()

    # GeoNames raw text -> convert to cities.db.
    if rtype == "geocoding" and ext == ".txt":
        count = _convert_geonames_to_db(tmp_path, target_dir / "cities.db")
        _safe_unlink(tmp_path)
        return f"已从 GeoNames 生成城市库 ({count} 条)"

    allowed = ALLOWED_EXTENSIONS[rtype]
    if ext not in allowed:
        _safe_unlink(tmp_path)
        raise ResourceDownloadError(
            f"{rtype} 资源不接受扩展名 “{ext or '(空)'}”，仅支持: {', '.join(sorted(allowed))}"
        )

    if rtype == "geocoding":
        dest = target_dir / "cities.db"
    elif rtype == "scene":
        dest = target_dir / ("scene.onnx" if ext == ".onnx" else "labels_zh.json")
    else:  # face — preserve original name so det_*/w600k_* globs match
        dest = target_dir / _sanitize_name(source_name)

    _atomic_place(tmp_path, dest)
    return f"已安装 {dest.name}"


def _install_from_zip(rtype: str, tmp_path: Path, target_dir: Path) -> str:
    """Extract a downloaded zip and install its relevant members."""
    extract_dir = Path(tempfile.mkdtemp(dir=str(target_dir), prefix="unzip_"))
    installed: list[str] = []
    try:
        try:
            with zipfile.ZipFile(tmp_path) as zf:
                # Guard against zip-slip: reject absolute/traversal members.
                for member in zf.namelist():
                    if member.startswith("/") or ".." in Path(member).parts:
                        raise ResourceDownloadError("压缩包包含非法路径")
                zf.extractall(extract_dir)
        except zipfile.BadZipFile as exc:
            raise ResourceDownloadError(f"无效的压缩包: {exc}") from exc

        all_files = [p for p in extract_dir.rglob("*") if p.is_file()]

        if rtype == "face":
            onnx = [p for p in all_files if p.suffix.lower() == ".onnx"]
            if not onnx:
                raise ResourceDownloadError("压缩包内未找到 .onnx 模型")
            for p in onnx:
                _atomic_place(p, target_dir / p.name)
                installed.append(p.name)

        elif rtype == "scene":
            onnx = [p for p in all_files if p.suffix.lower() == ".onnx"]
            jsons = [p for p in all_files if p.suffix.lower() == ".json"]
            if onnx:
                _atomic_place(onnx[0], target_dir / "scene.onnx")
                installed.append("scene.onnx")
            if jsons:
                _atomic_place(jsons[0], target_dir / "labels_zh.json")
                installed.append("labels_zh.json")
            if not installed:
                raise ResourceDownloadError("压缩包内未找到 .onnx / .json")

        else:  # geocoding
            db = [p for p in all_files if p.suffix.lower() == ".db"]
            if db:
                _atomic_place(db[0], target_dir / "cities.db")
                installed.append("cities.db")
            else:
                # Prefer a plausible GeoNames dump (largest .txt).
                txts = sorted(
                    (p for p in all_files if p.suffix.lower() == ".txt"),
                    key=lambda p: p.stat().st_size,
                    reverse=True,
                )
                # Skip GeoNames metadata files that are not the geoname table.
                txts = [p for p in txts if p.name.lower() != "readme.txt"]
                if not txts:
                    raise ResourceDownloadError("压缩包内未找到 .db 或 GeoNames .txt")
                count = _convert_geonames_to_db(txts[0], target_dir / "cities.db")
                installed.append(f"cities.db ({count} 条)")

        return "已安装: " + ", ".join(installed)
    finally:
        _safe_unlink(tmp_path)
        shutil.rmtree(extract_dir, ignore_errors=True)


def download_and_install(rtype: str, url: str, models_root: str) -> str:
    """Download ``url`` and install it as the ``rtype`` resource.

    Synchronous (blocking) — call from a worker thread. Returns a human-readable
    success message. Raises :class:`ResourceDownloadError` on any failure; on
    failure existing installed files are left untouched (temp files are used and
    only atomically moved into place after successful validation/extraction).
    """
    if rtype not in RESOURCE_DIRS:
        raise ResourceDownloadError(
            f"未知的资源类型: {rtype}（应为 face / scene / geocoding）"
        )

    target_dir = Path(models_root) / RESOURCE_DIRS[rtype]
    target_dir.mkdir(parents=True, exist_ok=True)

    def _report(downloaded: int, total: Optional[int]) -> None:
        _update_progress(rtype, phase="downloading", downloaded=downloaded, total=total)

    tmp_path, source_name = _fetch_to_tmp(url, target_dir, progress=_report)

    # Extraction / conversion phase (indeterminate — no byte progress).
    _update_progress(rtype, phase="installing")

    if source_name.lower().endswith(".zip"):
        return _install_from_zip(rtype, tmp_path, target_dir)
    return _install_single_file(rtype, tmp_path, source_name, target_dir)
