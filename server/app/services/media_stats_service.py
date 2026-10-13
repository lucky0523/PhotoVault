"""Media statistics aggregation (design §5.1, §5.2, §5.5).

All counting happens in SQLite. The in-scope rows of the current user are copied
once into the connection-local temp table ``temp.stats_scope`` together with the
derived columns (category, extension, local capture time, weekday, hour, month);
the eleven ``GROUP BY`` queries then run on that table. Python only receives the
aggregate rows, whose number depends on bucket counts, distinct extensions /
cameras / lenses and the month span, never on the number of files (AC30).

UTC-only capture times are converted with ``temp.stats_tz``, an interval table of
``(utc_start, '<±N> seconds')`` rows built by
:func:`app.services.stats_buckets.utc_offset_intervals`, so the conversion is
also done row-by-row inside SQL.

The photo scene EV (EV100, ``scene_ev`` column) is computed row-by-row inside SQL by
``pv_ev100``, a Python function registered on the connection by
:func:`app.services.exposure_value.register_sql_functions` — the same code as
the pure helper, so there is no second (SQL) copy of the formula.

The caller (``app.api.stats``) is responsible for validating and normalising the
path; this module only asserts it.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import aiosqlite

from app.services.exposure_value import SQL_EV100_FUNCTION, register_sql_functions
from app.services.media_metadata import CURRENT_META_VERSION
from app.services.stats_buckets import (
    APERTURE_SPEC,
    CATEGORY_BUCKETS,
    DURATION_SPEC,
    FOCAL_SPEC,
    FORMAT_CHILDREN_MAX,
    FORMAT_GROUP_LABELS,
    FORMAT_GROUPS,
    FORMAT_GROUPS_WITH_CHILDREN,
    FORMAT_REST_KEY,
    FORMAT_REST_LABEL,
    HOUR_BUCKETS,
    IMAGE_EXTS,
    ISO_SPEC,
    SCENE_EV_SPEC,
    SHUTTER_SPEC,
    UNKNOWN_KEY,
    WEEKDAY_BUCKETS,
    BucketSpec,
    allocate_shares,
    bucket_case_sql,
    bucket_distribution,
    build_ranking,
    build_trends,
    camera_display_name,
    ev_summary,
    format_group,
    lens_display_name,
    pct,
    spec_items,
)

# Filters shared by every scope (FR-2.4).
_COMMON_FILTER = (
    "fr.user_id = ? AND fr.deleted_at IS NULL AND fr.purged_at IS NULL "
    "AND (fr.is_reference IS NULL OR fr.is_reference = 0) "
    "AND instr(fr.file_path, '/.trash/') = 0"
)

_IMAGE_EXT_PLACEHOLDERS = ", ".join("?" for _ in IMAGE_EXTS)

_CREATE_SCOPE_SQL = f"""
CREATE TEMP TABLE stats_scope AS
WITH raw AS (
  SELECT fr.*,
         lower(CASE WHEN instr(fr.file_name, '.') = 0 THEN ''
                    ELSE substr(fr.file_name,
                                length(rtrim(fr.file_name, replace(fr.file_name, '.', ''))) + 1)
               END) AS ext
  FROM file_records fr
  WHERE {_COMMON_FILTER} {{scope}}
), base AS (
  SELECT
    CASE WHEN COALESCE(media_type, 'image') = 'video' THEN 'video'
         WHEN lower(COALESCE(mime_type, '')) LIKE 'image/%' OR ext IN ({_IMAGE_EXT_PLACEHOLDERS}) THEN 'photo'
         ELSE 'other' END                                          AS cat,
    ext,
    COALESCE(is_motion_photo, 0)                                   AS is_motion,
    CASE WHEN meta_version IS NULL OR meta_version < ?
              OR (meta_status = 'no_ffprobe' AND ? = 1)
         THEN 1 ELSE 0 END                                         AS pending,
    capture_time_kind                                              AS tkind,
    CASE capture_time_kind
      WHEN 'local' THEN capture_time
      WHEN 'utc'   THEN datetime(capture_time,
                        (SELECT z.modifier FROM temp.stats_tz z
                          WHERE z.utc_start <= capture_time
                          ORDER BY z.utc_start DESC LIMIT 1))
    END                                                            AS lt_raw,
    file_size, iso, duration_sec, focal_35mm, focal_35mm_source,
    f_number, exposure_time, camera_make, camera_model, lens_model
  FROM raw
), valid AS (
  SELECT *, CASE WHEN lt_raw IS NOT NULL
                  AND CAST(substr(lt_raw, 1, 4) AS INTEGER) BETWEEN 1900 AND ?
                  AND strftime('%w', lt_raw) IS NOT NULL
                 THEN lt_raw END AS lt
  FROM base
)
SELECT cat, ext, is_motion, pending, file_size,
       CASE WHEN lt IS NULL THEN NULL ELSE tkind END AS tkind,
       CAST(strftime('%w', lt) AS INTEGER) AS wd,
       CAST(strftime('%H', lt) AS INTEGER) AS hr,
       substr(lt, 1, 7)                    AS ym,
       iso, duration_sec, focal_35mm, focal_35mm_source,
       f_number, exposure_time, camera_make, camera_model, lens_model,
       CASE WHEN cat = 'photo' THEN {SQL_EV100_FUNCTION}(iso, f_number, exposure_time) END AS scene_ev
FROM valid
"""

# Aggregations #1–#3, #9–#11 (design §5.5). #4–#8 are built from BucketSpecs.
_Q_CAT_EXT = (
    "SELECT cat, ext, COUNT(*), SUM(is_motion), SUM(pending), SUM(file_size) "
    "FROM temp.stats_scope GROUP BY cat, ext"
)
_Q_WEEKDAY = (
    "SELECT cat, wd, tkind, COUNT(*) FROM temp.stats_scope "
    "WHERE cat IN ('photo', 'video') GROUP BY cat, wd, tkind"
)
_Q_HOUR = (
    "SELECT cat, hr, tkind, COUNT(*) FROM temp.stats_scope "
    "WHERE cat IN ('photo', 'video') GROUP BY cat, hr, tkind"
)
_Q_CAMERA = (
    "SELECT cat, camera_make, camera_model, COUNT(*) FROM temp.stats_scope "
    "WHERE cat IN ('photo', 'video') GROUP BY cat, camera_make, camera_model"
)
_Q_LENS = (
    "SELECT lens_model, COUNT(*) FROM temp.stats_scope "
    "WHERE cat = 'photo' GROUP BY lens_model"
)
_Q_TREND = (
    "SELECT cat, ym, COUNT(*) FROM temp.stats_scope "
    "WHERE cat IN ('photo', 'video') AND ym IS NOT NULL GROUP BY cat, ym"
)
# Scene EV summary: sample count / mean, then the median by LIMIT/OFFSET.
_Q_SCENE_EV_STATS = (
    "SELECT COUNT(scene_ev), AVG(scene_ev) FROM temp.stats_scope "
    "WHERE cat = 'photo' AND scene_ev IS NOT NULL"
)
_Q_SCENE_EV_MEDIAN = (
    "SELECT AVG(scene_ev) FROM (SELECT scene_ev FROM temp.stats_scope "
    "WHERE cat = 'photo' AND scene_ev IS NOT NULL "
    "ORDER BY scene_ev LIMIT ? OFFSET ?)"
)


def _float_or_none(value: object) -> float | None:
    """SQLite AVG() result -> float; NULL stays None."""
    return None if value is None else float(value)


def _assert_normalized(path: str) -> None:
    """Path normalisation is the API layer's job; only assert it here."""
    assert path == path.strip("/"), "path must not start or end with '/'"
    assert "\x00" not in path and "\\" not in path, "path contains illegal characters"
    if path:
        assert all(seg not in ("", ".", "..") for seg in path.split("/")), (
            "path contains empty, '.' or '..' segments"
        )


class MediaStatsService:
    """Compute the statistics for one user and one directory scope."""

    def __init__(self, db: aiosqlite.Connection, media_root: str):
        self._db = db
        self._media_root = media_root

    def _scope_sql(
        self, user_id: int, username: str, path: str, recursive: bool
    ) -> tuple[str, list[Any]]:
        """``(extra WHERE fragment, params)`` after the common filter (§5.1)."""
        params: list[Any] = [user_id]
        if path == "":
            return "", params
        prefix = f"{self._media_root}/{username}/{path}/"
        hi = prefix[:-1] + "0"  # '0' is the character right after '/'
        fragment = "AND fr.file_path >= ? AND fr.file_path < ?"
        params += [prefix, hi]
        if not recursive:
            fragment += " AND instr(substr(fr.file_path, ?), '/') = 0"
            params.append(len(prefix) + 1)
        return fragment, params

    async def _rows(self, sql: str, params: Sequence[Any] = ()) -> list[tuple]:
        cursor = await self._db.execute(sql, params)
        try:
            return [tuple(r) for r in await cursor.fetchall()]
        finally:
            await cursor.close()

    async def _bucket_counts(
        self, spec: BucketSpec, cat: str, extra_col: str | None = None
    ) -> list[tuple]:
        case_sql, params = bucket_case_sql(spec)
        cols = f"{case_sql} AS b" + (f", {extra_col}" if extra_col else "")
        group = "1, 2" if extra_col else "1"
        sql = f"SELECT {cols}, COUNT(*) FROM temp.stats_scope WHERE cat = ? GROUP BY {group}"
        return await self._rows(sql, [*params, cat])

    async def _drop_temp_tables(self) -> None:
        await self._db.execute("DROP TABLE IF EXISTS temp.stats_scope")
        await self._db.execute("DROP TABLE IF EXISTS temp.stats_tz")
        await self._db.commit()

    async def compute(
        self,
        user_id: int,
        username: str,
        path: str,
        recursive: bool,
        tz_intervals: Sequence[tuple[str, str]],
        has_ffprobe: bool,
        top_n: int,
        max_year: int,
    ) -> dict[str, Any]:
        """Return ``{'pending_metadata_count', 'overview', 'photo', 'video'}``.

        Values are plain dicts shaped like the response models in
        ``app.api.stats``. Raises ``aiosqlite.Error`` on database failures.
        """
        _assert_normalized(path)
        db = self._db
        try:
            await db.execute("PRAGMA temp_store = MEMORY")
            await register_sql_functions(db)
            await self._drop_temp_tables()
            await db.execute(
                "CREATE TEMP TABLE stats_tz("
                "utc_start TEXT PRIMARY KEY, modifier TEXT NOT NULL)"
            )
            await db.executemany(
                "INSERT INTO temp.stats_tz(utc_start, modifier) VALUES (?, ?)",
                list(tz_intervals),
            )
            scope_fragment, scope_params = self._scope_sql(
                user_id, username, path, recursive
            )
            await db.execute(
                _CREATE_SCOPE_SQL.format(scope=scope_fragment),
                [
                    *scope_params,
                    *IMAGE_EXTS,
                    CURRENT_META_VERSION,
                    1 if has_ffprobe else 0,
                    max_year,
                ],
            )
            # End the implicit transaction so no read snapshot is held while
            # the aggregations run on the temp table.
            await db.commit()
            return await self._aggregate(top_n)
        finally:
            await self._drop_temp_tables()

    async def _aggregate(self, top_n: int) -> dict[str, Any]:
        # 1. category / extension totals
        cat_count = {"photo": 0, "video": 0, "other": 0}
        cat_bytes = {"photo": 0, "video": 0, "other": 0}
        group_count: dict[str, int] = {}
        group_bytes: dict[str, int] = {}
        children: dict[str, dict[str, list[int]]] = {}
        motion = 0
        pending = 0
        for cat, ext, count, is_motion, pend, size in await self._rows(_Q_CAT_EXT):
            count, size = int(count), int(size or 0)
            cat_count[cat] += count
            cat_bytes[cat] += size
            pending += int(pend or 0)
            if cat == "photo":
                motion += int(is_motion or 0)
            ext = ext or ""
            group = format_group(ext, cat)
            group_count[group] = group_count.get(group, 0) + count
            group_bytes[group] = group_bytes.get(group, 0) + size
            if group in FORMAT_GROUPS_WITH_CHILDREN:
                child = children.setdefault(group, {}).setdefault(ext, [0, 0])
                child[0] += count
                child[1] += size

        total_count = sum(cat_count.values())
        total_bytes = sum(cat_bytes.values())
        overview = {
            "total": {"count": total_count, "bytes": total_bytes},
            "motion_photo_count": motion,
            "categories": self._categories(cat_count, cat_bytes, total_count, total_bytes),
            "formats": self._formats(
                group_count, group_bytes, children, total_count, total_bytes
            ),
        }

        photo: dict[str, Any] = {}
        video: dict[str, Any] = {}

        # 2–3. weekday / hour
        for name, sql, enum in (
            ("weekday", _Q_WEEKDAY, WEEKDAY_BUCKETS),
            ("hour", _Q_HOUR, HOUR_BUCKETS),
        ):
            per_cat: dict[str, dict[int, int]] = {"photo": {}, "video": {}}
            unknown = {"photo": 0, "video": 0}
            meta = {
                "photo": {"local": 0, "utc_converted": 0},
                "video": {"local": 0, "utc_converted": 0},
            }
            for cat, value, tkind, count in await self._rows(sql):
                count = int(count)
                if value is None:
                    unknown[cat] += count
                    continue
                per_cat[cat][int(value)] = per_cat[cat].get(int(value), 0) + count
                if tkind == "utc":
                    meta[cat]["utc_converted"] += count
                else:
                    meta[cat]["local"] += count
            for cat, target in (("photo", photo), ("video", video)):
                key_counts = {key: per_cat[cat].get(v, 0) for v, key, _ in enum}
                target[name] = bucket_distribution(
                    [(key, label, None) for _, key, label in enum],
                    key_counts,
                    unknown[cat],
                    meta=meta[cat],
                )

        # 4–8. numeric buckets
        photo["iso"] = await self._spec_distribution(ISO_SPEC, "photo")

        focal_counts: dict[str, int] = {}
        focal_meta = {"by_exif": 0, "by_derived": 0}
        for key, source, count in await self._bucket_counts(
            FOCAL_SPEC, "photo", "focal_35mm_source"
        ):
            count = int(count)
            focal_counts[key] = focal_counts.get(key, 0) + count
            if key != UNKNOWN_KEY:
                if source == "exif":
                    focal_meta["by_exif"] += count
                elif source == "derived":
                    focal_meta["by_derived"] += count
        photo["focal_length"] = self._from_counts(FOCAL_SPEC, focal_counts, focal_meta)

        photo["aperture"] = await self._spec_distribution(APERTURE_SPEC, "photo")
        photo["shutter"] = await self._spec_distribution(SHUTTER_SPEC, "photo")

        # scene EV: buckets + samples / median / mean (all in SQL)
        scene = await self._spec_distribution(SCENE_EV_SPEC, "photo")
        n, mean = (await self._rows(_Q_SCENE_EV_STATS))[0]
        median = None
        if n:
            median = (await self._rows(_Q_SCENE_EV_MEDIAN, [2 - n % 2, (n - 1) // 2]))[0][0]
        scene["summary"] = ev_summary(int(n), _float_or_none(median), _float_or_none(mean))
        photo["scene_ev"] = scene
        video["duration"] = await self._spec_distribution(DURATION_SPEC, "video")

        # 9. cameras
        camera_pairs: dict[str, list[tuple[str | None, int]]] = {"photo": [], "video": []}
        for cat, make, model, count in await self._rows(_Q_CAMERA):
            camera_pairs[cat].append((camera_display_name(make, model), int(count)))
        photo["camera"] = build_ranking(camera_pairs["photo"], cat_count["photo"], top_n)
        video["camera"] = build_ranking(camera_pairs["video"], cat_count["video"], top_n)

        # 10. lenses
        lens_pairs = [
            (lens_display_name(lens), int(count))
            for lens, count in await self._rows(_Q_LENS)
        ]
        photo["lens"] = build_ranking(lens_pairs, cat_count["photo"], top_n)

        # 11. trends on a shared axis
        trends = build_trends(
            await self._rows(_Q_TREND),
            {"photo": cat_count["photo"], "video": cat_count["video"]},
        )
        photo["monthly"] = trends["photo"]["monthly"]
        photo["yearly"] = trends["photo"]["yearly"]
        video["monthly"] = trends["video"]["monthly"]
        video["yearly"] = trends["video"]["yearly"]

        return {
            "pending_metadata_count": pending,
            "overview": overview,
            "photo": photo,
            "video": video,
        }

    async def _spec_distribution(self, spec: BucketSpec, cat: str) -> dict[str, Any]:
        counts = {key: int(count) for key, count in await self._bucket_counts(spec, cat)}
        return self._from_counts(spec, counts)

    @staticmethod
    def _from_counts(
        spec: BucketSpec, counts: dict[str, int], meta: dict[str, int] | None = None
    ) -> dict[str, Any]:
        unknown = counts.pop(UNKNOWN_KEY, 0)
        return bucket_distribution(spec_items(spec), counts, unknown, meta=meta)

    @staticmethod
    def _categories(
        cat_count: dict[str, int],
        cat_bytes: dict[str, int],
        total_count: int,
        total_bytes: int,
    ) -> dict[str, Any]:
        keys = [key for key, _ in CATEGORY_BUCKETS]
        shares = allocate_shares([*(cat_count[k] for k in keys), 0], total_count)
        byte_shares = allocate_shares([cat_bytes[k] for k in keys], total_bytes)
        return {
            "kind": "distribution",
            "buckets": [
                {
                    "key": key,
                    "label": label,
                    "count": cat_count[key],
                    "share": shares[i],
                    "bytes": cat_bytes[key],
                    "bytes_share": byte_shares[i],
                }
                for i, (key, label) in enumerate(CATEGORY_BUCKETS)
            ],
            "unknown": 0,
            "unknown_share": shares[-1],
            "total": total_count,
            "total_bytes": total_bytes,
        }

    @staticmethod
    def _formats(
        group_count: dict[str, int],
        group_bytes: dict[str, int],
        children: dict[str, dict[str, list[int]]],
        total_count: int,
        total_bytes: int,
    ) -> dict[str, Any]:
        groups = [key for key, _, _ in FORMAT_GROUPS if group_count.get(key, 0) > 0]
        shares = allocate_shares([*(group_count[g] for g in groups), 0], total_count)
        byte_shares = allocate_shares([group_bytes[g] for g in groups], total_bytes)
        buckets = []
        for i, group in enumerate(groups):
            bucket: dict[str, Any] = {
                "key": group,
                "label": FORMAT_GROUP_LABELS[group],
                "count": group_count[group],
                "share": shares[i],
                "bytes": group_bytes[group],
                "bytes_share": byte_shares[i],
            }
            if group in FORMAT_GROUPS_WITH_CHILDREN:
                items = sorted(children.get(group, {}).items(), key=lambda kv: (-kv[1][0], kv[0]))
                kept = items[:FORMAT_CHILDREN_MAX]
                rest = items[FORMAT_CHILDREN_MAX:]
                entries = [(ext, ext.upper(), c, b) for ext, (c, b) in kept]
                if rest:
                    entries.append((
                        FORMAT_REST_KEY,
                        FORMAT_REST_LABEL,
                        sum(c for _, (c, _) in rest),
                        sum(b for _, (_, b) in rest),
                    ))
                bucket["children"] = [
                    {
                        "key": key,
                        "label": label,
                        "count": c,
                        "share": pct(c, total_count),
                        "bytes": b,
                        "bytes_share": pct(b, total_bytes),
                    }
                    for key, label, c, b in entries
                ]
            buckets.append(bucket)
        return {
            "kind": "distribution",
            "buckets": buckets,
            "unknown": 0,
            "unknown_share": shares[-1],
            "total": total_count,
            "total_bytes": total_bytes,
        }
