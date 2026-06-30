"""Tests for StoragePathEngine.

Covers:
- All 4 path resolution combinations
- sanitize_device_name with various inputs
- validate_path with valid and invalid paths
- Time extraction priority
- Sub-folder handling
- Edge cases
"""

from datetime import datetime

import pytest

from app.models.storage import FileMetadata, PathValidationResult, StoragePolicy
from app.services.storage_path_engine import StoragePathEngine


# ---------------------------------------------------------------------------
# sanitize_device_name tests
# ---------------------------------------------------------------------------


class TestSanitizeDeviceName:
    """Tests for StoragePathEngine.sanitize_device_name()."""

    def test_simple_valid_name(self):
        """Letters, digits, underscores, and hyphens are kept."""
        assert StoragePathEngine.sanitize_device_name("Pixel9Pro") == "Pixel9Pro"

    def test_name_with_hyphens_and_underscores(self):
        """Hyphens and underscores are preserved."""
        assert StoragePathEngine.sanitize_device_name("My-Device_2") == "My-Device_2"

    def test_spaces_replaced(self):
        """Spaces are replaced with underscores."""
        assert StoragePathEngine.sanitize_device_name("My Phone") == "My_Phone"

    def test_special_characters_replaced(self):
        """Special characters are replaced with underscores and collapsed."""
        assert StoragePathEngine.sanitize_device_name("iPhone (15) Pro!") == "iPhone_15_Pro"

    def test_unicode_characters_replaced(self):
        """Unicode characters are replaced with underscores."""
        assert StoragePathEngine.sanitize_device_name("手机设备") == "unknown_device"

    def test_mixed_unicode_and_ascii(self):
        """Mixed unicode and ASCII keeps only ASCII parts."""
        assert StoragePathEngine.sanitize_device_name("我的Pixel手机") == "Pixel"

    def test_consecutive_underscores_collapsed(self):
        """Multiple consecutive underscores are collapsed into one."""
        assert StoragePathEngine.sanitize_device_name("a___b") == "a_b"

    def test_leading_trailing_underscores_stripped(self):
        """Leading and trailing underscores are stripped."""
        assert StoragePathEngine.sanitize_device_name("__device__") == "device"

    def test_empty_string_returns_default(self):
        """Empty string returns 'unknown_device'."""
        assert StoragePathEngine.sanitize_device_name("") == "unknown_device"

    def test_only_special_chars_returns_default(self):
        """String with only special characters returns 'unknown_device'."""
        assert StoragePathEngine.sanitize_device_name("@#$%^&*()") == "unknown_device"

    def test_dots_replaced(self):
        """Dots are replaced with underscores."""
        assert StoragePathEngine.sanitize_device_name("Samsung.Galaxy.S24") == "Samsung_Galaxy_S24"

    def test_slashes_replaced(self):
        """Slashes are replaced with underscores."""
        assert StoragePathEngine.sanitize_device_name("device/name") == "device_name"

    def test_single_character(self):
        """Single valid character is kept."""
        assert StoragePathEngine.sanitize_device_name("X") == "X"

    def test_single_invalid_character(self):
        """Single invalid character returns default."""
        assert StoragePathEngine.sanitize_device_name("@") == "unknown_device"


# ---------------------------------------------------------------------------
# validate_path tests
# ---------------------------------------------------------------------------


class TestValidatePath:
    """Tests for StoragePathEngine.validate_path()."""

    def test_valid_simple_path(self):
        """Simple valid path passes validation."""
        result = StoragePathEngine.validate_path("/data/alice/Pixel9Pro/DCIM/Camera")
        assert result.is_valid is True
        assert result.error_message == ""

    def test_valid_path_with_year_month(self):
        """Path with year/month directories passes validation."""
        result = StoragePathEngine.validate_path("/data/alice/Pixel9Pro/DCIM/Camera/2026/03")
        assert result.is_valid is True

    def test_null_byte_rejected(self):
        """Path containing null byte is rejected."""
        result = StoragePathEngine.validate_path("/data/alice\x00/test")
        assert result.is_valid is False
        assert "illegal characters" in result.error_message

    def test_newline_rejected(self):
        """Path containing newline is rejected."""
        result = StoragePathEngine.validate_path("/data/alice\n/test")
        assert result.is_valid is False
        assert "illegal characters" in result.error_message

    def test_carriage_return_rejected(self):
        """Path containing carriage return is rejected."""
        result = StoragePathEngine.validate_path("/data/alice\r/test")
        assert result.is_valid is False
        assert "illegal characters" in result.error_message

    def test_folder_name_at_limit(self):
        """Folder name exactly at 255 characters passes."""
        folder_name = "a" * 255
        result = StoragePathEngine.validate_path(f"/data/{folder_name}/test")
        assert result.is_valid is True

    def test_folder_name_exceeds_limit(self):
        """Folder name exceeding 255 characters is rejected."""
        folder_name = "a" * 256
        result = StoragePathEngine.validate_path(f"/data/{folder_name}/test")
        assert result.is_valid is False
        assert "255 characters" in result.error_message

    def test_path_at_limit(self):
        """Path exactly at 4096 characters passes."""
        # Build a path that is exactly 4096 chars with valid folder names (≤255 each)
        # Use repeated segments of "/aaaa...a" (each folder name 200 chars)
        segment = "/" + "a" * 200  # 201 chars per segment
        num_segments = 4096 // 201  # ~20 segments
        path = segment * num_segments
        # Pad to exactly 4096
        remaining = 4096 - len(path)
        if remaining > 0:
            path = path + "/" + "b" * (remaining - 1)
        path = path[:4096]
        result = StoragePathEngine.validate_path(path)
        assert result.is_valid is True

    def test_path_exceeds_limit(self):
        """Path exceeding 4096 characters is rejected."""
        path = "/data/" + "a" * 4096
        result = StoragePathEngine.validate_path(path)
        assert result.is_valid is False
        assert "4096 characters" in result.error_message

    def test_empty_path_valid(self):
        """Empty path passes validation (no illegal chars, within limits)."""
        result = StoragePathEngine.validate_path("")
        assert result.is_valid is True

    def test_path_with_spaces_valid(self):
        """Path with spaces is valid (spaces are not illegal filesystem chars)."""
        result = StoragePathEngine.validate_path("/data/my folder/photos")
        assert result.is_valid is True

    def test_path_with_unicode_valid(self):
        """Path with unicode characters is valid (no illegal chars)."""
        result = StoragePathEngine.validate_path("/data/用户/照片")
        assert result.is_valid is True


# ---------------------------------------------------------------------------
# resolve_path tests — four combinations
# ---------------------------------------------------------------------------


class TestResolvePathCombinations:
    """Tests for the four storage policy combinations."""

    def test_not_manual_no_year_month(self):
        """Combination 1: {root}/{user}/{device}/{source_folder}/"""
        policy = StoragePolicy(use_custom_path=False, use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/"

    def test_not_manual_with_year_month(self):
        """Combination 2: {root}/{user}/{device}/{source_folder}/{year}/{month}/"""
        policy = StoragePolicy(use_custom_path=False, use_year_month_layer=True)
        metadata = FileMetadata(exif_time=datetime(2026, 3, 15, 10, 30, 0))

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/2026/03/"

    def test_manual_no_year_month(self):
        """Combination 3: {custom_path}/{source_folder}/"""
        policy = StoragePolicy(
            use_custom_path=True,
            custom_path="/data/alice/travel",
            use_year_month_layer=False,
        )
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/travel/DCIM/Camera/"

    def test_manual_with_year_month(self):
        """Combination 4: {custom_path}/{source_folder}/{year}/{month}/"""
        policy = StoragePolicy(
            use_custom_path=True,
            custom_path="/data/alice/travel",
            use_year_month_layer=True,
        )
        metadata = FileMetadata(exif_time=datetime(2026, 3, 15, 10, 30, 0))

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/travel/DCIM/Camera/2026/03/"


# ---------------------------------------------------------------------------
# resolve_path — time extraction priority
# ---------------------------------------------------------------------------


class TestTimeExtractionPriority:
    """Tests for year/month time extraction priority."""

    def test_exif_time_takes_priority(self):
        """EXIF time is used when both EXIF and file creation time are present."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(
            exif_time=datetime(2026, 3, 15),
            file_created_time=datetime(2025, 12, 1),
        )

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert "2026/03" in result
        assert "2025/12" not in result

    def test_file_created_time_fallback(self):
        """File creation time is used when EXIF time is not available."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(
            exif_time=None,
            file_created_time=datetime(2025, 12, 1),
        )

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert "2025/12" in result

    def test_unknown_date_fallback(self):
        """'unknown_date' is used when neither EXIF nor file creation time is available."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(exif_time=None, file_created_time=None)

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/unknown_date/"

    def test_year_month_format_zero_padded(self):
        """Month is zero-padded to 2 digits."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(exif_time=datetime(2024, 1, 5))

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="bob",
            device_name="iPhone15",
            source_folder="Photos",
            policy=policy,
            file_metadata=metadata,
        )

        assert "2024/01" in result

    def test_year_four_digits(self):
        """Year is always 4 digits."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(exif_time=datetime(999, 6, 15))

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="device",
            source_folder="photos",
            policy=policy,
            file_metadata=metadata,
        )

        assert "0999/06" in result


# ---------------------------------------------------------------------------
# resolve_path — sub-folder handling
# ---------------------------------------------------------------------------


class TestSubFolderHandling:
    """Tests for sub-folder path preservation logic."""

    def test_subfolder_without_year_month(self):
        """Sub-folder is appended after source_folder when no year/month."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata(sub_folder="burst")

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/burst/"

    def test_subfolder_with_year_month(self):
        """Year/month is appended after sub-folder path."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(
            sub_folder="burst",
            exif_time=datetime(2026, 3, 15),
        )

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/burst/2026/03/"

    def test_nested_subfolder(self):
        """Nested sub-folders are preserved."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(
            sub_folder="vacation/day1",
            exif_time=datetime(2026, 7, 20),
        )

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/vacation/day1/2026/07/"

    def test_subfolder_with_manual_path(self):
        """Sub-folder works with manual custom path."""
        policy = StoragePolicy(
            use_custom_path=True,
            custom_path="/data/alice/travel",
            use_year_month_layer=True,
        )
        metadata = FileMetadata(
            sub_folder="burst",
            exif_time=datetime(2026, 3, 15),
        )

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/travel/DCIM/Camera/burst/2026/03/"

    def test_empty_subfolder_ignored(self):
        """Empty sub-folder string is ignored."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata(sub_folder="")

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/"

    def test_subfolder_with_leading_trailing_slashes(self):
        """Sub-folder with leading/trailing slashes is normalized."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata(sub_folder="/burst/")

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/burst/"


# ---------------------------------------------------------------------------
# resolve_path — edge cases
# ---------------------------------------------------------------------------


class TestResolvePathEdgeCases:
    """Edge case tests for resolve_path."""

    def test_source_folder_with_leading_slash(self):
        """Source folder with leading slash is normalized."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="/DCIM/Camera/",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/"

    def test_storage_root_with_trailing_slash(self):
        """Storage root with trailing slash doesn't cause double slashes."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data/",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/DCIM/Camera/"

    def test_custom_path_with_trailing_slash(self):
        """Custom path with trailing slash doesn't cause double slashes."""
        policy = StoragePolicy(
            use_custom_path=True,
            custom_path="/data/alice/travel/",
            use_year_month_layer=False,
        )
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/travel/DCIM/Camera/"

    def test_device_name_sanitized_in_path(self):
        """Device name is sanitized when used in path."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="My Phone (2024)",
            source_folder="DCIM",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/My_Phone_2024/DCIM/"

    def test_empty_source_folder(self):
        """Empty source folder doesn't add extra slashes."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="",
            policy=policy,
            file_metadata=metadata,
        )

        assert result == "/data/alice/Pixel9Pro/"

    def test_manual_path_with_no_custom_path_falls_back(self):
        """When use_custom_path is True but custom_path is None, falls back to default."""
        policy = StoragePolicy(
            use_custom_path=True,
            custom_path=None,
            use_year_month_layer=False,
        )
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM",
            policy=policy,
            file_metadata=metadata,
        )

        # Falls back to default path since custom_path is None
        assert result == "/data/alice/Pixel9Pro/DCIM/"

    def test_result_always_ends_with_slash(self):
        """Result always ends with a forward slash."""
        policy = StoragePolicy(use_year_month_layer=False)
        metadata = FileMetadata()

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="Pixel9Pro",
            source_folder="DCIM/Camera",
            policy=policy,
            file_metadata=metadata,
        )

        assert result.endswith("/")

    def test_december_month_format(self):
        """December (month 12) is formatted correctly."""
        policy = StoragePolicy(use_year_month_layer=True)
        metadata = FileMetadata(exif_time=datetime(2025, 12, 31))

        result = StoragePathEngine.resolve_path(
            storage_root="/data",
            username="alice",
            device_name="device",
            source_folder="photos",
            policy=policy,
            file_metadata=metadata,
        )

        assert "2025/12" in result
