"""Unit tests for app.services.stats_buckets (design §5.3–§5.6, §10).

Bucket boundaries are checked by running ``bucket_case_sql`` in an in-memory
SQLite database, exactly as the statistics queries do.
"""

from __future__ import annotations

import logging
import sqlite3
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import pytest
from PIL.TiffImagePlugin import IFDRational

from app.core.config import Settings
from app.services import stats_buckets as sb
from app.services.stats_buckets import (
    APERTURE_SPEC,
    DURATION_SPEC,
    FOCAL_SPEC,
    ISO_SPEC,
    SCENE_EV_SPEC,
    SHUTTER_SPEC,
    Bucket,
    BucketSpec,
    ResolvedTz,
    allocate_shares,
    bucket_case_sql,
    ev_summary,
    build_ranking,
    build_trends,
    camera_display_name,
    format_group,
    lens_display_name,
    resolve_default_tz,
    utc_offset_intervals,
)


# ---------------------------------------------------------------------------
# bucket_case_sql in SQLite
# ---------------------------------------------------------------------------


def _bucket_of(spec: BucketSpec, value) -> str:
    case_sql, params = bucket_case_sql(spec)
    conn = sqlite3.connect(":memory:")
    try:
        conn.execute(f"CREATE TABLE t ({spec.column} REAL)")
        conn.execute("INSERT INTO t VALUES (?)", (value,))
        return conn.execute(f"SELECT {case_sql} FROM t", params).fetchone()[0]
    finally:
        conn.close()


def _check(spec: BucketSpec, cases: dict) -> None:
    got = {value: _bucket_of(spec, value) for value in cases}
    assert got == cases


class TestBucketCaseSql:
    def test_iso_ac16(self):
        _check(ISO_SPEC, {
            50: "iso_le_50",
            51: "iso_51_100",
            100: "iso_51_100",
            12799: "iso_6401_12799",
            12800: "iso_ge_12800",
            None: "__unknown",
            0: "__unknown",
        })

    def test_focal_ac17(self):
        _check(FOCAL_SPEC, {
            24: "fl_17_24",
            16: "fl_le_16",
            16.1: "fl_17_24",
            200: "fl_136_200",
            200.1: "fl_gt_200",
            None: "__unknown",
        })

    def test_aperture_ac18(self):
        _check(APERTURE_SPEC, {
            1.4: "ap_le_1_4",
            1.59: "ap_le_1_4",
            1.6: "ap_1_8",
            1.8: "ap_1_8",
            2.2: "ap_2",
            2.8: "ap_2_8",
            16: "ap_16_plus",
            22: "ap_16_plus",
            None: "__unknown",
            0: "__unknown",
        })

    def test_aperture_each_threshold_is_lower_bound(self):
        _check(APERTURE_SPEC, {
            1.9: "ap_2",
            2.4: "ap_2_8",
            3.4: "ap_4",
            4.8: "ap_5_6",
            6.7: "ap_8",
            9.5: "ap_11",
            13.5: "ap_16_plus",
        })

    def test_shutter_ac19(self):
        _check(SHUTTER_SPEC, {
            1 / 8000: "ss_le_1_4000",
            1 / 4000: "ss_le_1_4000",
            float(IFDRational(1, 2000)): "ss_1_4000_1_2000",
            1 / 1984: "ss_1_2000_1_1000",
            1 / 30: "ss_1_60_1_30",
            0.5: "ss_1_30_1",
            1: "ss_1_30_1",
            2: "ss_gt_1",
            None: "__unknown",
        })

    def test_shutter_tolerance_absorbs_rational_error(self):
        assert _bucket_of(SHUTTER_SPEC, 1 / 2000 * (1 + 5e-7)) == "ss_1_4000_1_2000"

    def test_duration_ac23(self):
        _check(DURATION_SPEC, {
            0: "__unknown",
            9.9: "d_lt_10",
            10: "d_10_30",
            59.9: "d_30_60",
            60: "d_60_180",
            600: "d_ge_600",
            None: "__unknown",
        })

    def test_negative_is_unknown(self):
        assert _bucket_of(ISO_SPEC, -5) == "__unknown"

    def test_thresholds_are_bound_parameters(self):
        case_sql, params = bucket_case_sql(ISO_SPEC)
        assert "?" in case_sql and "50" not in case_sql.replace("iso_le_50", "")
        assert len(params) == len(ISO_SPEC.buckets) - 1
        _, shutter_params = bucket_case_sql(SHUTTER_SPEC)
        assert shutter_params[0] == pytest.approx(1 / 4000 * (1 + 1e-6), rel=0, abs=1e-18)

    def test_spec_constants(self):
        assert [b.key for b in ISO_SPEC.buckets][-1] == "iso_ge_12800"
        assert len(ISO_SPEC.buckets) == 10
        assert len(FOCAL_SPEC.buckets) == 8
        assert len(APERTURE_SPEC.buckets) == 9
        assert len(SHUTTER_SPEC.buckets) == 10
        assert len(DURATION_SPEC.buckets) == 6
        assert SHUTTER_SPEC.rel_tolerance == 1e-6
        assert SHUTTER_SPEC.buckets[8].label == "1/15–1s"
        assert SHUTTER_SPEC.buckets[8].range == "(1/30, 1]"
        assert DURATION_SPEC.buckets[0].range == "(0, 10)"
        assert DURATION_SPEC.buckets[-1].label == "≥10分钟"


class TestValidateSpec:
    def _spec(self, buckets, mode="upper_inclusive", rel_tolerance=0.0):
        return BucketSpec("x", mode, tuple(buckets), rel_tolerance)

    def test_valid(self):
        sb._validate_spec(self._spec([Bucket("a", "a", 1, ""), Bucket("b", "b", None, "")]))

    def test_non_increasing(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(self._spec([
                Bucket("a", "a", 2, ""), Bucket("b", "b", 1, ""), Bucket("c", "c", None, ""),
            ]))

    def test_last_must_be_open(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(self._spec([Bucket("a", "a", 1, ""), Bucket("b", "b", 2, "")]))

    def test_duplicate_keys(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(self._spec([Bucket("a", "a", 1, ""), Bucket("a", "b", None, "")]))

    def test_tolerance_requires_upper_inclusive(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(self._spec(
                [Bucket("a", "a", 1, ""), Bucket("b", "b", None, "")],
                mode="lower_inclusive",
                rel_tolerance=1e-6,
            ))

    def test_tolerance_must_be_smaller_than_gaps(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(self._spec(
                [Bucket("a", "a", 1, ""), Bucket("b", "b", 1.5, ""), Bucket("c", "c", None, "")],
                rel_tolerance=0.6,
            ))

    def test_signed_rejects_tolerance(self):
        with pytest.raises(AssertionError):
            sb._validate_spec(BucketSpec(
                "x", "upper_inclusive",
                (Bucket("a", "a", 1, ""), Bucket("b", "b", None, "")),
                rel_tolerance=1e-6, signed=True,
            ))


# ---------------------------------------------------------------------------
# Scene EV spec (scene-ev design §4.2; AC7)
# ---------------------------------------------------------------------------

# bucket_case_sql output before the ``signed`` field was added.
_LEGACY_CASE_SQL = {
    "ISO_SPEC": "CASE WHEN iso IS NULL OR iso <= 0 THEN '__unknown' WHEN iso <= ? THEN 'iso_le_50' WHEN iso <= ? THEN 'iso_51_100' WHEN iso <= ? THEN 'iso_101_200' WHEN iso <= ? THEN 'iso_201_400' WHEN iso <= ? THEN 'iso_401_800' WHEN iso <= ? THEN 'iso_801_1600' WHEN iso <= ? THEN 'iso_1601_3200' WHEN iso <= ? THEN 'iso_3201_6400' WHEN iso <= ? THEN 'iso_6401_12799' ELSE 'iso_ge_12800' END",  # noqa: E501
    "FOCAL_SPEC": "CASE WHEN focal_35mm IS NULL OR focal_35mm <= 0 THEN '__unknown' WHEN focal_35mm <= ? THEN 'fl_le_16' WHEN focal_35mm <= ? THEN 'fl_17_24' WHEN focal_35mm <= ? THEN 'fl_25_35' WHEN focal_35mm <= ? THEN 'fl_36_50' WHEN focal_35mm <= ? THEN 'fl_51_85' WHEN focal_35mm <= ? THEN 'fl_86_135' WHEN focal_35mm <= ? THEN 'fl_136_200' ELSE 'fl_gt_200' END",  # noqa: E501
    "APERTURE_SPEC": "CASE WHEN f_number IS NULL OR f_number <= 0 THEN '__unknown' WHEN f_number < ? THEN 'ap_le_1_4' WHEN f_number < ? THEN 'ap_1_8' WHEN f_number < ? THEN 'ap_2' WHEN f_number < ? THEN 'ap_2_8' WHEN f_number < ? THEN 'ap_4' WHEN f_number < ? THEN 'ap_5_6' WHEN f_number < ? THEN 'ap_8' WHEN f_number < ? THEN 'ap_11' ELSE 'ap_16_plus' END",  # noqa: E501
    "SHUTTER_SPEC": "CASE WHEN exposure_time IS NULL OR exposure_time <= 0 THEN '__unknown' WHEN exposure_time <= ? THEN 'ss_le_1_4000' WHEN exposure_time <= ? THEN 'ss_1_4000_1_2000' WHEN exposure_time <= ? THEN 'ss_1_2000_1_1000' WHEN exposure_time <= ? THEN 'ss_1_1000_1_500' WHEN exposure_time <= ? THEN 'ss_1_500_1_250' WHEN exposure_time <= ? THEN 'ss_1_250_1_125' WHEN exposure_time <= ? THEN 'ss_1_125_1_60' WHEN exposure_time <= ? THEN 'ss_1_60_1_30' WHEN exposure_time <= ? THEN 'ss_1_30_1' ELSE 'ss_gt_1' END",  # noqa: E501
    "DURATION_SPEC": "CASE WHEN duration_sec IS NULL OR duration_sec <= 0 THEN '__unknown' WHEN duration_sec < ? THEN 'd_lt_10' WHEN duration_sec < ? THEN 'd_10_30' WHEN duration_sec < ? THEN 'd_30_60' WHEN duration_sec < ? THEN 'd_60_180' WHEN duration_sec < ? THEN 'd_180_600' ELSE 'd_ge_600' END",  # noqa: E501
}


class TestSceneEvSpec:
    def test_boundaries_ac7(self):
        _check(SCENE_EV_SPEC, {
            -5.51: "ev_le_m6",
            -5.5: "ev_m5",
            3.49: "ev_3",
            3.5: "ev_4",
            19.49: "ev_19",
            19.5: "ev_ge_20",
            0: "ev_0",
            -0.4: "ev_0",
            -3: "ev_m3",
            None: "__unknown",
        })

    def test_keys_and_labels(self):
        buckets = SCENE_EV_SPEC.buckets
        assert len(buckets) == 27
        assert [b.key for b in buckets] == (
            ["ev_le_m6"] + [f"ev_m{k}" for k in range(5, 0, -1)]
            + [f"ev_{k}" for k in range(0, 20)] + ["ev_ge_20"]
        )
        assert [b.label for b in buckets] == (
            ["≤−6"] + [f"−{k}" for k in range(5, 0, -1)]
            + [str(k) for k in range(0, 20)] + ["≥20"]
        )
        assert SCENE_EV_SPEC.signed is True
        assert SCENE_EV_SPEC.mode == "lower_inclusive"
        assert SCENE_EV_SPEC.column == "scene_ev"

    def test_ranges_are_contiguous(self):
        ranges = [b.range for b in SCENE_EV_SPEC.buckets]
        assert all(ranges)
        assert ranges[0] == "(−∞, −5.5)"
        assert ranges[1] == "[−5.5, −4.5)"
        assert ranges[-1] == "[19.5, ∞)"
        for prev, nxt in zip(ranges, ranges[1:]):
            right = prev[1:-1].split(", ")[1]
            left = nxt[1:-1].split(", ")[0]
            assert right == left, (prev, nxt)

    def test_text_helpers(self):
        assert sb._ev_text(-5.5) == "−5.5"
        assert sb._ev_text(0) == "0"
        assert sb._ev_text(19.5) == "19.5"
        assert sb._ev_key(-5) == "ev_m5"
        assert sb._ev_key(0) == "ev_0"
        assert sb._ev_key(19) == "ev_19"

    def test_signed_unknown_condition(self):
        case_sql, params = bucket_case_sql(SCENE_EV_SPEC)
        assert case_sql.startswith("CASE WHEN scene_ev IS NULL THEN '__unknown'")
        assert "<= 0" not in case_sql
        assert len(params) == 26

    @pytest.mark.parametrize("name", sorted(_LEGACY_CASE_SQL))
    def test_existing_specs_sql_unchanged(self, name):
        assert bucket_case_sql(getattr(sb, name))[0] == _LEGACY_CASE_SQL[name]

    def test_ev_summary(self):
        assert ev_summary(0, 1.0, 2.0) == {"samples": 0, "median": None, "mean": None}
        assert ev_summary(3, 12.25, 9.123456789) == {
            "samples": 3, "median": 12.25, "mean": 9.123456789,
        }


# ---------------------------------------------------------------------------
# allocate_shares
# ---------------------------------------------------------------------------


class TestAllocateShares:
    @pytest.mark.parametrize("counts", [[1] * 7, [1, 1, 1], [333, 333, 334], [5], [2, 1], [0, 3, 0]])
    def test_tenths_sum_to_1000(self, counts):
        shares = allocate_shares(counts, sum(counts))
        assert sum(round(s * 10) for s in shares) == 1000
        assert all(round(s * 10) == s * 10 for s in shares)

    def test_largest_remainder_ties_go_to_lower_index(self):
        assert allocate_shares([1, 1, 1], 3) == [33.4, 33.3, 33.3]

    def test_zero_total(self):
        assert allocate_shares([0, 0, 0], 0) == [0.0, 0.0, 0.0]

    def test_mismatched_total_asserts(self):
        with pytest.raises(AssertionError):
            allocate_shares([1, 2], 4)


# ---------------------------------------------------------------------------
# Time zone resolution and interval table
# ---------------------------------------------------------------------------


def _rtz(cache_key: str) -> ResolvedTz:
    return ResolvedTz(sb._tz_from_cache_key(cache_key), cache_key, "request", cache_key)


def _local(intervals, utc_iso: str) -> str:
    """Apply the interval table to a UTC timestamp in SQLite, like the stats SQL."""
    conn = sqlite3.connect(":memory:")
    try:
        conn.execute("CREATE TABLE z (utc_start TEXT PRIMARY KEY, modifier TEXT NOT NULL)")
        conn.executemany("INSERT INTO z VALUES (?, ?)", intervals)
        return conn.execute(
            "SELECT datetime(?, (SELECT modifier FROM z WHERE utc_start <= ? "
            "ORDER BY utc_start DESC LIMIT 1))",
            (utc_iso, utc_iso),
        ).fetchone()[0]
    finally:
        conn.close()


class TestUtcOffsetIntervals:
    def test_fixed_offset_single_row(self):
        assert utc_offset_intervals(_rtz("fixed:+28800"), 2027) == (
            ("0000-01-01T00:00:00", "+28800 seconds"),
        )

    def test_etc_gmt_minus_8_single_row(self):
        assert utc_offset_intervals(_rtz("iana:Etc/GMT-8"), 2027) == (
            ("0000-01-01T00:00:00", "+28800 seconds"),
        )

    def test_asia_shanghai_history(self):
        rows = utc_offset_intervals(_rtz("iana:Asia/Shanghai"), 2027)
        assert rows[0] == ("0000-01-01T00:00:00", "+29143 seconds")
        assert any(s.startswith("1986-05") and m == "+32400 seconds" for s, m in rows)
        assert rows[-1][0] < "1992-01-01"
        assert rows[-1][1] == "+28800 seconds"
        # Not merged: every row differs from its predecessor.
        assert all(a[1] != b[1] for a, b in zip(rows, rows[1:]))

    def test_shanghai_1988_summer_is_plus_9(self):
        rows = utc_offset_intervals(_rtz("iana:Asia/Shanghai"), 2027)
        assert _local(rows, "1988-07-01T00:00:00") == "1988-07-01 09:00:00"
        assert _local(rows, "2025-01-15T15:30:00") == "2025-01-15 23:30:00"

    def test_new_york_dst_switch_exact(self):
        rows = utc_offset_intervals(_rtz("iana:America/New_York"), 2027)
        assert ("2025-03-09T07:00:00", "-14400 seconds") in rows
        assert _local(rows, "2025-03-09T06:59:59") == "2025-03-09 01:59:59"
        assert _local(rows, "2025-03-09T07:00:00") == "2025-03-09 03:00:00"

    def test_cached(self):
        a = utc_offset_intervals(_rtz("iana:Europe/Berlin"), 2027)
        b = utc_offset_intervals(_rtz("iana:Europe/Berlin"), 2027)
        assert a is b


class TestResolveDefaultTz:
    @pytest.fixture(autouse=True)
    def _reset(self):
        sb._reset_tz_warnings()
        yield
        sb._reset_tz_warnings()

    def _settings(self, value: str = "") -> Settings:
        return Settings(storage_root="/data/photovault", default_timezone=value)

    def test_config_wins_over_request(self):
        rtz = resolve_default_tz("UTC", self._settings("Asia/Shanghai"))
        assert (rtz.name, rtz.source, rtz.cache_key) == (
            "Asia/Shanghai", "config", "iana:Asia/Shanghai",
        )

    def test_request_when_no_config(self):
        rtz = resolve_default_tz("America/New_York", self._settings())
        assert (rtz.name, rtz.source) == ("America/New_York", "request")
        assert isinstance(rtz.tzinfo, ZoneInfo)

    def test_tz_env_when_no_request(self, monkeypatch):
        monkeypatch.setenv("TZ", "Europe/Berlin")
        rtz = resolve_default_tz(None, self._settings())
        assert (rtz.name, rtz.source, rtz.cache_key) == (
            "Europe/Berlin", "server", "env:Europe/Berlin",
        )

    def test_local_fixed_offset_last(self, monkeypatch):
        monkeypatch.delenv("TZ", raising=False)
        rtz = resolve_default_tz(None, self._settings())
        assert rtz.source == "server"
        assert rtz.name.startswith("UTC+") or rtz.name.startswith("UTC-")
        assert rtz.cache_key.startswith("fixed:")
        seconds = int(rtz.cache_key.split(":", 1)[1])
        expected = datetime.now().astimezone().utcoffset().total_seconds()
        assert seconds == int(expected)

    def test_invalid_tz_env_falls_back_to_local(self, monkeypatch):
        monkeypatch.setenv("TZ", "Not/AZone")
        rtz = resolve_default_tz(None, self._settings())
        assert rtz.cache_key.startswith("fixed:")

    def test_invalid_config_skipped_with_single_warning(self, caplog):
        settings = self._settings("Not/AZone")
        with caplog.at_level(logging.WARNING, logger="photovault.stats"):
            first = resolve_default_tz("Asia/Tokyo", settings)
            second = resolve_default_tz("Asia/Tokyo", settings)
        assert first.source == "request" and second.source == "request"
        warnings = [r for r in caplog.records if "default_timezone" in r.getMessage()]
        assert len(warnings) == 1

    def test_fixed_offset_name_format(self):
        assert sb._format_fixed_offset(28800) == "UTC+08:00"
        assert sb._format_fixed_offset(-16200) == "UTC-04:30"
        assert sb._format_fixed_offset(0) == "UTC+00:00"


# ---------------------------------------------------------------------------
# Extension SQL, formats
# ---------------------------------------------------------------------------

_EXT_SQL = (
    "lower(CASE WHEN instr(?1, '.') = 0 THEN '' "
    "ELSE substr(?1, length(rtrim(?1, replace(?1, '.', ''))) + 1) END)"
)


class TestExtensionSql:
    @pytest.mark.parametrize(
        "name, ext",
        [
            ("照片.JPG", "jpg"),
            (".jpg", "jpg"),
            ("README", ""),
            ("trailing.", ""),
            ("archive.tar.GZ", "gz"),
            ("IMG_0001.HEIC", "heic"),
        ],
    )
    def test_extension(self, name, ext):
        conn = sqlite3.connect(":memory:")
        try:
            assert conn.execute(f"SELECT {_EXT_SQL}", (name,)).fetchone()[0] == ext
        finally:
            conn.close()

    def test_service_uses_same_expression(self):
        from app.services import media_stats_service

        assert (
            "length(rtrim(fr.file_name, replace(fr.file_name, '.', ''))) + 1"
            in media_stats_service._CREATE_SCOPE_SQL
        )


class TestFormatGroup:
    def test_order_of_rules(self):
        assert format_group("", "video") == "no_ext"
        assert format_group("jpg", "video") == "jpeg"
        assert format_group("mkv", "video") == "video_other"
        assert format_group("txt", "other") == "misc"
        assert format_group("dng", "photo") == "raw"
        assert format_group("heif", "photo") == "heic"
        assert format_group("mov", "video") == "mov"

    def test_image_exts_include_raw(self):
        for ext in sb.RAW_EXTS:
            assert ext in sb.IMAGE_EXTS


# ---------------------------------------------------------------------------
# Rankings
# ---------------------------------------------------------------------------


class TestCameraDisplayName:
    def test_samples(self):
        assert camera_display_name("Canon", "Canon EOS R5") == "Canon EOS R5"
        assert camera_display_name("NIKON CORPORATION", "NIKON D850") == "NIKON D850"
        assert camera_display_name("Apple", "iPhone 15 Pro") == "Apple iPhone 15 Pro"
        assert (
            camera_display_name("apple ", "iPhone 15 Pro").casefold()
            == camera_display_name("Apple", "iPhone 15 Pro").casefold()
        )
        assert camera_display_name("Canon", None) is None
        assert camera_display_name("Canon", "  ") is None
        assert camera_display_name(None, "Pixel 9 Pro") == "Pixel 9 Pro"
        assert camera_display_name("Go\x00ogle  ", " Pixel   9\x00 ") == "Google Pixel 9"

    def test_lens(self):
        assert lens_display_name("  EF 24-70mm\x00 ") == "EF 24-70mm"
        assert lens_display_name("") is None


class TestBuildRanking:
    def test_top_n_other_unknown(self):
        pairs = [(f"Cam {i:02d}", 20 - i) for i in range(12)]  # 20, 19, ... 9
        pairs.append((None, 4))
        total = sum(c for _, c in pairs) + 3  # 3 rows with no camera row at all
        r = build_ranking(pairs, total, 10)
        assert r["kind"] == "ranking"
        assert [b["label"] for b in r["buckets"]] == [f"Cam {i:02d}" for i in range(10)]
        assert r["other"] == (20 - 10) + (20 - 11)
        assert r["unknown"] == 7
        assert sum(b["count"] for b in r["buckets"]) + r["other"] + r["unknown"] == r["total"]
        shares = [b["share"] for b in r["buckets"]] + [r["other_share"], r["unknown_share"]]
        assert sum(round(s * 10) for s in shares) == 1000

    def test_casefold_merge_and_label(self):
        r = build_ranking([("Apple iPhone", 2), ("apple iphone", 3), ("APPLE IPHONE", 3)], 8, 10)
        assert len(r["buckets"]) == 1
        bucket = r["buckets"][0]
        assert bucket["key"] == "apple iphone"
        assert bucket["count"] == 8
        # Highest count wins; ties -> lexicographically smallest spelling.
        assert bucket["label"] == "APPLE IPHONE"

    def test_tie_sorted_by_key(self):
        r = build_ranking([("b", 2), ("a", 2), ("c", 5)], 9, 10)
        assert [b["key"] for b in r["buckets"]] == ["c", "a", "b"]

    def test_top_n_one(self):
        r = build_ranking([("a", 5), ("b", 3), ("c", 1)], 10, 1)
        assert [b["key"] for b in r["buckets"]] == ["a"]
        assert r["other"] == 4
        assert r["unknown"] == 1

    def test_empty(self):
        r = build_ranking([(None, 3)], 3, 10)
        assert r["buckets"] == [] and r["other"] == 0 and r["unknown"] == 3
        r0 = build_ranking([], 0, 10)
        assert r0["total"] == 0 and r0["unknown_share"] == 0.0


# ---------------------------------------------------------------------------
# Trends
# ---------------------------------------------------------------------------


class TestBuildTrends:
    def test_shared_axis_zero_fill(self):
        t = build_trends(
            [("photo", "2023-11", 2), ("video", "2024-02", 1)],
            {"photo": 3, "video": 1},
        )
        months = ["2023-11", "2023-12", "2024-01", "2024-02"]
        assert [b["key"] for b in t["photo"]["monthly"]["buckets"]] == months
        assert [b["key"] for b in t["video"]["monthly"]["buckets"]] == months
        assert [b["count"] for b in t["photo"]["monthly"]["buckets"]] == [2, 0, 0, 0]
        assert [b["count"] for b in t["video"]["monthly"]["buckets"]] == [0, 0, 0, 1]
        assert t["photo"]["monthly"]["buckets"][0]["label"] == "2023年11月"
        assert t["photo"]["monthly"]["unknown"] == 1
        assert t["photo"]["monthly"]["kind"] == "series"
        assert "range" not in t["photo"]["monthly"]["buckets"][0]

    def test_yearly_summed_from_months(self):
        t = build_trends(
            [("photo", "2023-11", 2), ("photo", "2023-12", 1), ("photo", "2025-01", 4)],
            {"photo": 7, "video": 0},
        )
        yearly = t["photo"]["yearly"]["buckets"]
        assert [(b["key"], b["label"], b["count"]) for b in yearly] == [
            ("2023", "2023年", 3), ("2024", "2024年", 0), ("2025", "2025年", 4),
        ]
        assert [b["key"] for b in t["video"]["yearly"]["buckets"]] == ["2023", "2024", "2025"]
        assert len(t["photo"]["monthly"]["buckets"]) == 15

    def test_no_valid_time(self):
        t = build_trends([], {"photo": 2, "video": 1})
        for cat, total in (("photo", 2), ("video", 1)):
            for series in ("monthly", "yearly"):
                assert t[cat][series]["buckets"] == []
                assert t[cat][series]["unknown"] == total
                assert t[cat][series]["unknown_share"] == 100.0

    def test_cross_year(self):
        t = build_trends([("photo", "2023-12", 1), ("photo", "2024-01", 1)], {"photo": 2, "video": 0})
        assert [b["key"] for b in t["photo"]["monthly"]["buckets"]] == ["2023-12", "2024-01"]
