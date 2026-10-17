"""Tests for app.services.media_metadata (design §4.1–§4.5, §4.7)."""

import logging
import math
import shutil
import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import aiosqlite
import pytest
from PIL import Image
from PIL.ExifTags import IFD
from PIL.TiffImagePlugin import IFDRational

from app.core.database import init_db
from app.services import media_metadata as mm
from app.services.media_metadata import (
    BACKFILL_FFPROBE_TIMEOUT,
    CURRENT_META_VERSION,
    UPLOAD_FFPROBE_TIMEOUT,
    MediaMetadata,
    apply_media_metadata,
    apply_media_metadata_many,
    derive_crop_factor,
    extract_image_metadata,
    extract_media_metadata,
    ffprobe_path,
    normalize_text,
    parse_ffprobe_json,
    resolve_capture_time,
)

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


@pytest.fixture(autouse=True)
def _reset_ffprobe():
    mm._reset_ffprobe_cache()
    yield
    mm._reset_ffprobe_cache()


def make_jpeg(
    path: Path,
    *,
    size: tuple[int, int] = (64, 48),
    root: dict | None = None,
    exif_ifd: dict | None = None,
    fmt: str = "JPEG",
) -> Path:
    """Write a small image with the given root-IFD and Exif-IFD tags."""
    img = Image.new("RGB", size, "red")
    exif = Image.Exif()
    for tag, value in (root or {}).items():
        exif[tag] = value
    if exif_ifd:
        ifd = exif.get_ifd(IFD.Exif)
        for tag, value in exif_ifd.items():
            ifd[tag] = value
    img.save(path, format=fmt, exif=exif.tobytes())
    return path


FULL_ROOT = {271: "Canon", 272: "Canon EOS R5"}
FULL_EXIF = {
    34855: 800,
    33437: IFDRational(28, 10),
    33434: IFDRational(1, 2000),
    36867: "2024:05:01 18:30:12",
    37386: IFDRational(50, 1),
    41989: 50,
    42036: "RF24-70mm F2.8 L IS USM",
}


def _tiny_mp4(path: Path, *, extra: list[str] | None = None) -> Path:
    cmd = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=duration=3:size=64x48:rate=10",
        "-c:v", "mpeg4",
        "-metadata", "creation_time=2025-01-15T15:30:00Z",
        *(extra or []),
        str(path),
    ]
    subprocess.run(cmd, check=True, capture_output=True, timeout=60)
    return path


# ---------------------------------------------------------------------------
# Photos
# ---------------------------------------------------------------------------


class TestImageExtraction:
    def test_jpeg_all_fields(self, tmp_path):
        """AC25: every new column is filled from a camera JPEG."""
        path = make_jpeg(tmp_path / "a.jpg", root=FULL_ROOT, exif_ifd=FULL_EXIF)

        meta = extract_media_metadata(
            str(path), "image", None, ffprobe_timeout=UPLOAD_FFPROBE_TIMEOUT
        )

        assert meta.status == "ok"
        assert meta.capture_time == "2024-05-01T18:30:12"
        assert meta.capture_time_kind == "local"
        assert meta.iso == 800
        assert meta.f_number == 2.8
        assert meta.exposure_time == pytest.approx(1 / 2000)
        assert meta.focal_35mm == 50.0
        assert meta.focal_35mm_source == "exif"
        assert meta.focal_actual == 50.0
        assert meta.camera_make == "Canon"
        assert meta.camera_model == "Canon EOS R5"
        assert meta.lens_model == "RF24-70mm F2.8 L IS USM"
        assert meta.duration_sec is None

    def test_datetime_digitized_fallback_and_iso_fallback(self, tmp_path):
        path = make_jpeg(
            tmp_path / "b.jpg",
            exif_ifd={36868: "2023:01:02 03:04:05", 34867: 1600},
        )
        meta = extract_image_metadata(str(path))
        assert meta.capture_time == "2023-01-02T03:04:05"
        assert meta.capture_time_kind == "local"
        assert meta.iso == 1600

    def test_zero_datetime_is_ignored(self, tmp_path):
        path = make_jpeg(tmp_path / "z.jpg", exif_ifd={36867: "0000:00:00 00:00:00"})
        meta = extract_image_metadata(str(path))
        assert meta.capture_time is None
        assert meta.capture_time_kind is None

    def test_heic_read_through_open_image(self, tmp_path):
        """AC25 (automated part): HEIC is read through image_utils.open_image."""
        pillow_heif = pytest.importorskip("pillow_heif")
        pillow_heif.register_heif_opener()
        path = tmp_path / "c.heic"
        try:
            make_jpeg(path, root=FULL_ROOT, exif_ifd=FULL_EXIF, fmt="HEIF")
        except Exception as exc:  # pragma: no cover - environment dependent
            pytest.skip(f"pillow-heif cannot encode HEIC in this environment: {exc}")

        with patch.object(mm, "open_image", wraps=mm.open_image) as spy:
            meta = extract_image_metadata(str(path))
        spy.assert_called_once_with(str(path))
        assert meta.status == "ok"
        assert meta.iso == 800
        assert meta.camera_model == "Canon EOS R5"
        assert meta.focal_35mm == 50.0

    def test_only_actual_focal_length_is_unknown(self, tmp_path):
        """AC17: only FocalLength=50 and no crop factor -> no 35mm value."""
        path = make_jpeg(tmp_path / "d.jpg", exif_ifd={37386: IFDRational(50, 1)})
        meta = extract_image_metadata(str(path))
        assert meta.focal_actual == 50.0
        assert meta.focal_35mm is None
        assert meta.focal_35mm_source is None

    def test_zero_35mm_tag_counts_as_missing(self, tmp_path):
        path = make_jpeg(
            tmp_path / "e.jpg", exif_ifd={41989: 0, 37386: IFDRational(50, 1)}
        )
        meta = extract_image_metadata(str(path))
        assert meta.focal_35mm is None

    def test_derived_focal_length_from_focal_plane_tags(self, tmp_path):
        # APS-C sensor 22.3 x 14.9 mm, image 64 x 48 is not 3:2, so use 60 x 40.
        width_mm, height_mm = 22.3, 14.9
        path = make_jpeg(
            tmp_path / "f.jpg",
            size=(60, 40),
            exif_ifd={
                37386: IFDRational(50, 1),
                41486: IFDRational(round(60 / width_mm * 1_000_000), 1_000_000),
                41487: IFDRational(round(40 / height_mm * 1_000_000), 1_000_000),
                41488: 4,
                40962: 60,
                40963: 40,
            },
        )
        meta = extract_image_metadata(str(path))
        crop = 43.27 / math.hypot(width_mm, height_mm)
        assert meta.focal_35mm_source == "derived"
        assert meta.focal_35mm == round(50.0 * crop, 1)

    def test_nan_rational_is_null(self, tmp_path):
        """IFDRational(1, 0) is nan and must not be stored."""
        path = make_jpeg(
            tmp_path / "g.jpg",
            exif_ifd={33437: IFDRational(1, 0), 33434: IFDRational(1, 0), 41989: IFDRational(1, 0)},
        )
        meta = extract_image_metadata(str(path))
        assert meta.f_number is None
        assert meta.exposure_time is None
        assert meta.focal_35mm is None

    def test_out_of_range_values_are_null(self, tmp_path):
        path = make_jpeg(
            tmp_path / "h.jpg",
            exif_ifd={33434: IFDRational(7200, 1), 33437: IFDRational(200, 1), 34855: 0},
        )
        meta = extract_image_metadata(str(path))
        assert meta.exposure_time is None
        assert meta.f_number is None
        assert meta.iso is None

    def test_tuple_iso_takes_first_positive(self, tmp_path):
        path = make_jpeg(tmp_path / "i.jpg", exif_ifd={34855: (0, 320, 640)})
        assert extract_image_metadata(str(path)).iso == 320

    def test_corrupt_file_is_error_and_does_not_raise(self, tmp_path):
        path = tmp_path / "broken.jpg"
        path.write_bytes(b"\xff\xd8\xff not really a jpeg")
        meta = extract_media_metadata(
            str(path), "image", "2024-01-01T08:00:00", ffprobe_timeout=UPLOAD_FFPROBE_TIMEOUT
        )
        assert meta.status == "error"
        assert meta.capture_time == "2024-01-01T08:00:00"
        assert meta.capture_time_kind == "local"
        assert meta.iso is None

    def test_missing_file_is_error(self, tmp_path):
        meta = extract_media_metadata(
            str(tmp_path / "nope.jpg"), None, None, ffprobe_timeout=UPLOAD_FFPROBE_TIMEOUT
        )
        assert meta.status == "error"
        assert meta.capture_time is None


class TestDeriveCropFactor:
    FULL_FRAME = {
        41486: IFDRational(6000 * 1000, 36 * 1000),
        41487: IFDRational(4000 * 1000, 24 * 1000),
        41488: 4,
        40962: 6000,
        40963: 4000,
    }

    def test_full_frame_millimetres(self):
        crop = derive_crop_factor(self.FULL_FRAME, (6000, 4000))
        assert crop == pytest.approx(43.27 / math.hypot(36, 24))

    def test_inch_unit(self):
        tags = dict(self.FULL_FRAME)
        tags[41486] = 6000 / (22.3 / 25.4)
        tags[41487] = 4000 / (14.9 / 25.4)
        tags[41488] = 2
        crop = derive_crop_factor(tags, (6000, 4000))
        assert crop == pytest.approx(43.27 / math.hypot(22.3, 14.9))

    def test_centimetre_unit(self):
        tags = dict(self.FULL_FRAME)
        tags[41486] = 6000 / 3.6
        tags[41487] = 4000 / 2.4
        tags[41488] = 3
        assert derive_crop_factor(tags, (6000, 4000)) == pytest.approx(
            43.27 / math.hypot(36, 24)
        )

    def test_swapped_image_size_is_accepted(self):
        assert derive_crop_factor(self.FULL_FRAME, (4000, 6000)) is not None

    @pytest.mark.parametrize("missing", [41486, 41487, 41488, 40962, 40963])
    def test_missing_tag(self, missing):
        tags = {k: v for k, v in self.FULL_FRAME.items() if k != missing}
        assert derive_crop_factor(tags, (6000, 4000)) is None

    def test_resized_image_is_rejected(self):
        assert derive_crop_factor(self.FULL_FRAME, (1500, 1000)) is None

    def test_unknown_unit_is_rejected(self):
        tags = dict(self.FULL_FRAME)
        tags[41488] = 1
        assert derive_crop_factor(tags, (6000, 4000)) is None

    @pytest.mark.parametrize("bad", [0, -5, IFDRational(1, 0)])
    def test_invalid_resolution(self, bad):
        tags = dict(self.FULL_FRAME)
        tags[41486] = bad
        assert derive_crop_factor(tags, (6000, 4000)) is None

    def test_out_of_range_factor(self):
        # 6000 px over 1 mm -> diagonal ~1.2 mm -> crop ~36 (> 10)
        tags = dict(self.FULL_FRAME)
        tags[41486] = 6000
        tags[41487] = 4000
        assert derive_crop_factor(tags, (6000, 4000)) is None
        # 6000 px over 360 mm -> crop ~0.1 (< 0.5)
        tags[41486] = 6000 / 360
        tags[41487] = 4000 / 240
        assert derive_crop_factor(tags, (6000, 4000)) is None


# ---------------------------------------------------------------------------
# Capture time, text normalisation
# ---------------------------------------------------------------------------


class TestResolveCaptureTime:
    def test_file_value_wins(self):
        assert resolve_capture_time(
            ("2024-01-01T10:00:00", "local"), "2020-01-01T00:00:00", "video"
        ) == ("2024-01-01T10:00:00", "local")

    def test_photo_client_is_local_wall_clock(self):
        assert resolve_capture_time(None, "2025-01-15T15:30:00+00:00", "image") == (
            "2025-01-15T15:30:00",
            "local",
        )
        assert resolve_capture_time(None, "2025-01-15 15:30:00", None) == (
            "2025-01-15T15:30:00",
            "local",
        )

    def test_video_client_naive_is_utc(self):
        assert resolve_capture_time(None, "2025-01-15T15:30:00", "video") == (
            "2025-01-15T15:30:00",
            "utc",
        )

    def test_video_client_zero_offset_is_utc(self):
        assert resolve_capture_time(None, "2025-01-15T15:30:00+00:00", "video") == (
            "2025-01-15T15:30:00",
            "utc",
        )

    def test_video_client_nonzero_offset_is_local(self):
        assert resolve_capture_time(None, "2025-01-15T23:30:00+08:00", "video") == (
            "2025-01-15T23:30:00",
            "local",
        )

    def test_fractional_seconds_are_dropped(self):
        assert resolve_capture_time(None, "2025-01-15T15:30:00.123456", "image") == (
            "2025-01-15T15:30:00",
            "local",
        )

    @pytest.mark.parametrize("value", [None, "", "not a date", "2025-13-45"])
    def test_missing_or_invalid(self, value):
        assert resolve_capture_time(None, value, "image") == (None, None)


class TestNormalizeText:
    def test_bytes_and_nul(self):
        assert normalize_text(b"Canon\x00\x00") == "Canon"

    def test_whitespace_collapses(self):
        assert normalize_text("  NIKON   CORPORATION \t\n") == "NIKON CORPORATION"

    def test_invalid_utf8_is_replaced(self):
        assert normalize_text(b"Len\xffs") == "Len\ufffds"

    def test_truncation(self):
        assert normalize_text("x" * 300) == "x" * 128

    @pytest.mark.parametrize("value", [None, "", "   ", "\x00\x00", b""])
    def test_empty_is_none(self, value):
        assert normalize_text(value) is None

    def test_non_string(self):
        assert normalize_text(42) == "42"


# ---------------------------------------------------------------------------
# Videos
# ---------------------------------------------------------------------------


class TestParseFfprobeJson:
    def test_quicktime_creationdate_is_local(self):
        meta = parse_ffprobe_json(
            {
                "format": {
                    "duration": "12.345678",
                    "tags": {
                        "com.apple.quicktime.creationdate": "2024-05-01T18:30:12+0800",
                        "creation_time": "2024-05-01T10:30:12.000000Z",
                        "com.apple.quicktime.make": "Apple",
                        "com.apple.quicktime.model": "iPhone 15 Pro",
                    },
                }
            }
        )
        assert meta.capture_time == "2024-05-01T18:30:12"
        assert meta.capture_time_kind == "local"
        assert meta.duration_sec == 12.346
        assert meta.camera_make == "Apple"
        assert meta.camera_model == "iPhone 15 Pro"
        assert meta.status == "ok"

    def test_creation_time_is_utc(self):
        meta = parse_ffprobe_json(
            {"format": {"tags": {"creation_time": "2025-01-15T15:30:00.000000Z"}}}
        )
        assert (meta.capture_time, meta.capture_time_kind) == ("2025-01-15T15:30:00", "utc")

    def test_creation_time_with_offset_is_converted_to_utc(self):
        meta = parse_ffprobe_json(
            {"format": {"tags": {"creation_time": "2025-01-15T23:30:00+08:00"}}}
        )
        assert (meta.capture_time, meta.capture_time_kind) == ("2025-01-15T15:30:00", "utc")

    @pytest.mark.parametrize(
        "zero", ["1904-01-01T00:00:00.000000Z", "1970-01-01T00:00:00Z"]
    )
    def test_zero_dates_are_dropped(self, zero):
        meta = parse_ffprobe_json({"format": {"tags": {"creation_time": zero}}})
        assert meta.capture_time is None
        assert meta.capture_time_kind is None

    def test_zero_quicktime_falls_back_to_creation_time(self):
        meta = parse_ffprobe_json(
            {
                "format": {
                    "tags": {
                        "com.apple.quicktime.creationdate": "1904-01-01T00:00:00",
                        "creation_time": "2025-01-15T15:30:00Z",
                    }
                }
            }
        )
        assert (meta.capture_time, meta.capture_time_kind) == ("2025-01-15T15:30:00", "utc")

    def test_tag_keys_are_case_insensitive(self):
        meta = parse_ffprobe_json(
            {
                "format": {
                    "tags": {
                        "CREATION_TIME": "2025-01-15T15:30:00Z",
                        "Make": "samsung",
                        "MODEL": "SM-S9180",
                    }
                }
            }
        )
        assert meta.capture_time == "2025-01-15T15:30:00"
        assert meta.camera_make == "samsung"
        assert meta.camera_model == "SM-S9180"

    def test_make_model_key_order(self):
        meta = parse_ffprobe_json(
            {
                "format": {
                    "tags": {
                        "make": "generic",
                        "model": "generic-model",
                        "com.android.manufacturer": "Google",
                        "com.android.model": "Pixel 9 Pro",
                        "com.apple.quicktime.make": " ",
                    }
                }
            }
        )
        assert meta.camera_make == "Google"
        assert meta.camera_model == "Pixel 9 Pro"

    def test_stream_duration_fallback(self):
        meta = parse_ffprobe_json(
            {
                "streams": [
                    {"codec_type": "audio", "duration": "9.0"},
                    {"codec_type": "video", "duration": "3.5"},
                    {"codec_type": "video", "duration": "4.25"},
                ],
                "format": {},
            }
        )
        assert meta.duration_sec == 4.25

    def test_invalid_values(self):
        meta = parse_ffprobe_json(
            {"format": {"duration": "N/A", "tags": {"creation_time": "garbage"}}}
        )
        assert meta.duration_sec is None
        assert meta.capture_time is None

    def test_empty(self):
        meta = parse_ffprobe_json({})
        assert meta == MediaMetadata(status="ok")


class TestFfprobePath:
    def test_not_found_is_not_cached_and_warns_once(self, caplog):
        with patch.object(mm.shutil, "which", return_value=None) as which:
            with caplog.at_level(logging.WARNING, logger="photovault.media_metadata"):
                assert ffprobe_path() is None
                assert ffprobe_path() is None
        assert which.call_count == 2
        warnings = [r for r in caplog.records if "ffprobe not found" in r.getMessage()]
        assert len(warnings) == 1

    def test_found_is_cached(self):
        with patch.object(mm.shutil, "which", return_value="/usr/bin/ffprobe") as which:
            assert ffprobe_path() == "/usr/bin/ffprobe"
            assert ffprobe_path() == "/usr/bin/ffprobe"
        assert which.call_count == 1

    def test_installed_later_is_found(self):
        with patch.object(mm.shutil, "which", return_value=None):
            assert ffprobe_path() is None
        with patch.object(mm.shutil, "which", return_value="/opt/ffprobe"):
            assert ffprobe_path() == "/opt/ffprobe"


class TestVideoExtraction:
    def test_no_ffprobe(self, tmp_path):
        """AC26: missing ffprobe -> 'no_ffprobe', capture time from the client."""
        path = tmp_path / "v.mp4"
        path.write_bytes(b"\x00" * 16)
        with patch.object(mm.shutil, "which", return_value=None):
            meta = extract_media_metadata(
                str(path), "video", "2025-01-15T15:30:00", ffprobe_timeout=UPLOAD_FFPROBE_TIMEOUT
            )
        assert meta.status == "no_ffprobe"
        assert meta.duration_sec is None
        assert (meta.capture_time, meta.capture_time_kind) == ("2025-01-15T15:30:00", "utc")

    def test_timeout(self, tmp_path):
        path = tmp_path / "v.mp4"
        path.write_bytes(b"\x00" * 16)
        run = MagicMock(side_effect=subprocess.TimeoutExpired(cmd="ffprobe", timeout=5))
        with patch.object(mm, "ffprobe_path", return_value="/fake/ffprobe"), patch.object(
            mm.subprocess, "run", run
        ):
            meta = extract_media_metadata(
                str(path), "video", "2025-01-15T15:30:00", ffprobe_timeout=UPLOAD_FFPROBE_TIMEOUT
            )
        assert meta.status == "timeout"
        assert meta.duration_sec is None
        assert meta.capture_time == "2025-01-15T15:30:00"
        cmd = run.call_args.args[0]
        assert cmd[0] == "/fake/ffprobe"
        assert cmd[1:-1] == [
            "-v", "error", "-print_format", "json",
            "-show_entries", "format=duration:format_tags:stream=codec_type,duration",
        ]
        assert cmd[-1] == str(path)
        assert run.call_args.kwargs["timeout"] == UPLOAD_FFPROBE_TIMEOUT
        assert run.call_args.kwargs["capture_output"] is True

    def test_nonzero_return_code_is_error(self, tmp_path):
        proc = subprocess.CompletedProcess(args=[], returncode=1, stdout=b"", stderr=b"bad")
        with patch.object(mm, "ffprobe_path", return_value="/fake/ffprobe"), patch.object(
            mm.subprocess, "run", return_value=proc
        ):
            meta = extract_media_metadata(
                str(tmp_path / "x.mp4"), "video", None, ffprobe_timeout=BACKFILL_FFPROBE_TIMEOUT
            )
        assert meta.status == "error"

    def test_invalid_json_is_error(self, tmp_path):
        proc = subprocess.CompletedProcess(args=[], returncode=0, stdout=b"{oops", stderr=b"")
        with patch.object(mm, "ffprobe_path", return_value="/fake/ffprobe"), patch.object(
            mm.subprocess, "run", return_value=proc
        ):
            meta = extract_media_metadata(
                str(tmp_path / "x.mp4"), "video", None, ffprobe_timeout=BACKFILL_FFPROBE_TIMEOUT
            )
        assert meta.status == "error"

    @pytest.mark.skipif(not HAS_FFMPEG, reason="ffmpeg/ffprobe not installed")
    def test_ffmpeg_generated_mp4(self, tmp_path):
        """Integration: a real 3 s MP4 with creation_time=...Z."""
        path = _tiny_mp4(tmp_path / "clip.mp4")
        meta = extract_media_metadata(
            str(path), "video", None, ffprobe_timeout=BACKFILL_FFPROBE_TIMEOUT
        )
        assert meta.status == "ok"
        assert meta.duration_sec == pytest.approx(3.0, abs=0.1)
        assert meta.capture_time == "2025-01-15T15:30:00"
        assert meta.capture_time_kind == "utc"

    @pytest.mark.skipif(not HAS_FFMPEG, reason="ffmpeg/ffprobe not installed")
    def test_ffprobe_on_corrupt_video_is_error(self, tmp_path):
        path = tmp_path / "broken.mp4"
        path.write_bytes(b"definitely not a video")
        meta = extract_media_metadata(
            str(path), "video", "2025-01-15T15:30:00", ffprobe_timeout=BACKFILL_FFPROBE_TIMEOUT
        )
        assert meta.status == "error"
        assert meta.capture_time_kind == "utc"


class TestDispatch:
    @pytest.mark.parametrize("media_type", [None, "image"])
    def test_non_video_goes_to_image_extractor(self, media_type):
        with patch.object(mm, "extract_image_metadata", return_value=MediaMetadata()) as img, \
                patch.object(mm, "extract_video_metadata") as vid:
            extract_media_metadata("/x", media_type, None, ffprobe_timeout=1.0)
        img.assert_called_once_with("/x")
        vid.assert_not_called()

    def test_video_goes_to_ffprobe(self):
        with patch.object(mm, "extract_image_metadata") as img, \
                patch.object(mm, "extract_video_metadata", return_value=MediaMetadata()) as vid:
            extract_media_metadata("/x", "video", None, ffprobe_timeout=7.0)
        vid.assert_called_once_with("/x", 7.0)
        img.assert_not_called()

    def test_extractor_exception_is_caught(self):
        with patch.object(mm, "extract_image_metadata", side_effect=RuntimeError("boom")):
            meta = extract_media_metadata(
                "/x", "image", "2024-02-03T04:05:06", ffprobe_timeout=1.0
            )
        assert meta.status == "error"
        assert meta.capture_time == "2024-02-03T04:05:06"


# ---------------------------------------------------------------------------
# Writers
# ---------------------------------------------------------------------------


@pytest.fixture
async def db(tmp_path):
    db_path = str(tmp_path / "meta.db")
    await init_db(db_path)
    conn = await aiosqlite.connect(db_path)
    conn.row_factory = aiosqlite.Row
    await conn.execute("INSERT INTO users (username, password_hash) VALUES ('alice', 'h')")
    await conn.execute(
        """INSERT INTO file_records
           (user_id, file_hash, file_path, original_path, device_name, file_size,
            file_name, media_type, iso, camera_make, meta_status)
           VALUES (1, 'h1', '/m/alice/a.mp4', '/DCIM/a.mp4', 'Pixel', 10, 'a.mp4',
                   'video', 100, 'OldMake', NULL)"""
    )
    await conn.commit()
    try:
        yield conn
    finally:
        await conn.close()


async def _row(db, file_id=1):
    cursor = await db.execute("SELECT * FROM file_records WHERE id = ?", (file_id,))
    return dict(await cursor.fetchone())


class TestApplyMediaMetadata:
    async def test_full_write(self, db):
        meta = MediaMetadata(
            capture_time="2025-01-15T15:30:00",
            capture_time_kind="utc",
            iso=400,
            focal_35mm=26.04,
            focal_35mm_source="exif",
            focal_actual=6.86,
            duration_sec=3.00049,
            f_number=1.777,
            exposure_time=1 / 8000,
            camera_make="  Google ",
            camera_model="Pixel 9 Pro",
            lens_model=None,
            status="ok",
        )
        await apply_media_metadata(db, 1, meta)
        await db.commit()
        row = await _row(db)
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"
        assert row["iso"] == 400
        assert row["focal_35mm"] == 26.0
        assert row["focal_35mm_source"] == "exif"
        assert row["focal_actual"] == 6.9
        assert row["duration_sec"] == 3.0
        assert row["f_number"] == 1.78
        assert row["exposure_time"] == 0.000125
        assert row["camera_make"] == "Google"
        assert row["camera_model"] == "Pixel 9 Pro"
        assert row["lens_model"] is None
        assert row["meta_version"] == CURRENT_META_VERSION
        assert row["meta_status"] == "ok"
        assert row["meta_scanned_at"]

    async def test_invalid_values_become_null(self, db):
        meta = MediaMetadata(
            capture_time="2025-01-15T15:30:00+08:00",
            capture_time_kind="local",
            iso=0,
            focal_35mm=float("nan"),
            focal_35mm_source="exif",
            duration_sec=-1.0,
            f_number=999.0,
            exposure_time=float("inf"),
            status="error",
        )
        await apply_media_metadata(db, 1, meta)
        row = await _row(db)
        assert row["capture_time"] is None
        assert row["capture_time_kind"] is None
        for col in ("iso", "focal_35mm", "focal_35mm_source", "duration_sec", "f_number", "exposure_time"):
            assert row[col] is None, col
        assert row["meta_status"] == "error"
        assert row["meta_version"] == CURRENT_META_VERSION

    async def test_timeout_only_writes_capture_time_and_status(self, db):
        meta = MediaMetadata(
            capture_time="2025-01-15T15:30:00",
            capture_time_kind="utc",
            iso=999,
            camera_make="NewMake",
            status="timeout",
        )
        await apply_media_metadata(db, 1, meta)
        await db.commit()
        row = await _row(db)
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"
        assert row["meta_status"] == "timeout"
        assert row["meta_scanned_at"]
        assert row["meta_version"] is None
        assert row["iso"] == 100
        assert row["camera_make"] == "OldMake"

    async def test_does_not_commit(self, db, tmp_path):
        await apply_media_metadata(db, 1, MediaMetadata(iso=200))
        await db.rollback()
        row = await _row(db)
        assert row["meta_version"] is None
        assert row["iso"] == 100

    async def test_many_with_optimistic_guard(self, db):
        await db.execute(
            """INSERT INTO file_records
               (user_id, file_hash, file_path, original_path, device_name, file_size, file_name)
               VALUES (1, 'h2', '/m/alice/b.jpg', '/DCIM/b.jpg', 'Pixel', 10, 'b.jpg')"""
        )
        await db.commit()
        updated = await apply_media_metadata_many(
            db,
            [
                (1, "/m/alice/a.mp4", "h1", MediaMetadata(iso=200)),
                (2, "/m/alice/stale-path.jpg", "h2", MediaMetadata(iso=300)),
            ],
        )
        await db.commit()
        assert updated == 1
        assert (await _row(db, 1))["iso"] == 200
        second = await _row(db, 2)
        assert second["iso"] is None
        assert second["meta_version"] is None

    async def test_many_skips_trashed_rows(self, db):
        await db.execute(
            "UPDATE file_records SET deleted_at = '2025-01-01T00:00:00' WHERE id = 1"
        )
        updated = await apply_media_metadata_many(
            db, [(1, "/m/alice/a.mp4", "h1", MediaMetadata(iso=200))]
        )
        assert updated == 0
        assert (await _row(db))["meta_version"] is None

    async def test_many_skips_rows_whose_hash_changed(self, db):
        """Content replaced in place (file versioning): same id and path, new hash."""
        await db.execute("UPDATE file_records SET file_hash = 'h1-new' WHERE id = 1")
        updated = await apply_media_metadata_many(
            db, [(1, "/m/alice/a.mp4", "h1", MediaMetadata(iso=200))]
        )
        assert updated == 0
        row = await _row(db)
        assert row["iso"] == 100
        assert row["meta_version"] is None
