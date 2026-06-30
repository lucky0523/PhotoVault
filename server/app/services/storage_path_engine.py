"""Storage path engine.

Resolves the target storage path based on storage policy configuration:
- Device name sanitization
- Path validation
- Four storage policy combinations
- Year/month time extraction
"""

from __future__ import annotations

import re

from app.models.storage import FileMetadata, PathValidationResult, StoragePolicy

# Characters that are illegal in filesystem paths
_ILLEGAL_PATH_CHARS = re.compile(r"[\x00\n\r]")

# Maximum length for a single folder name
_MAX_FOLDER_NAME_LENGTH = 255

# Maximum length for a complete path
_MAX_PATH_LENGTH = 4096

# Pattern for allowed characters in device names
_DEVICE_NAME_ALLOWED = re.compile(r"[^a-zA-Z0-9_\-]")

# Pattern for collapsing consecutive underscores
_CONSECUTIVE_UNDERSCORES = re.compile(r"_{2,}")


class StoragePathEngine:
    """Engine for resolving storage paths based on policy configuration.

    Handles four combinations of storage policy:
    1. Not manual + no year/month: {root}/{user}/{device}/{source_folder}/
    2. Not manual + year/month: {root}/{user}/{device}/{source_folder}/{year}/{month}/
    3. Manual + no year/month: {custom_path}/{source_folder}/
    4. Manual + year/month: {custom_path}/{source_folder}/{year}/{month}/
    """

    @staticmethod
    def resolve_path(
        storage_root: str,
        username: str,
        device_name: str,
        source_folder: str,
        policy: StoragePolicy,
        file_metadata: FileMetadata,
    ) -> str:
        """Resolve the target storage path for a file.

        Args:
            storage_root: The root storage directory on the NAS.
            username: The authenticated user's username.
            device_name: The device name (will be sanitized).
            source_folder: The source folder path from the mobile device.
            policy: The storage policy configuration.
            file_metadata: File metadata including timestamps and sub-folder.

        Returns:
            The resolved target directory path (always ends with /).
        """
        sanitized_device = StoragePathEngine.sanitize_device_name(device_name)

        # Normalize source_folder: strip leading/trailing slashes
        normalized_source = source_folder.strip("/")

        # Build base path depending on whether custom path is used
        if policy.use_custom_path and policy.custom_path:
            base_path = policy.custom_path.rstrip("/")
        else:
            base_path = f"{storage_root.rstrip('/')}/{username}/{sanitized_device}"

        # Append source folder
        if normalized_source:
            full_path = f"{base_path}/{normalized_source}"
        else:
            full_path = base_path

        # Append sub-folder if present
        sub_folder = file_metadata.sub_folder.strip("/")
        if sub_folder:
            full_path = f"{full_path}/{sub_folder}"

        # Append year/month layer if enabled
        if policy.use_year_month_layer:
            year_month = StoragePathEngine._extract_year_month(file_metadata)
            full_path = f"{full_path}/{year_month}"

        # Ensure path ends with /
        return f"{full_path}/"

    @staticmethod
    def validate_path(path: str) -> PathValidationResult:
        """Validate a filesystem path for illegal characters and length limits.

        Checks:
        - No illegal characters (null byte, newline, carriage return)
        - Each folder name ≤ 255 characters
        - Complete path ≤ 4096 characters

        Args:
            path: The path to validate.

        Returns:
            PathValidationResult with is_valid and error_message.
        """
        # Check for illegal characters
        if _ILLEGAL_PATH_CHARS.search(path):
            return PathValidationResult(
                is_valid=False,
                error_message="Path contains illegal characters (null byte, newline, or carriage return)",
            )

        # Check complete path length
        if len(path) > _MAX_PATH_LENGTH:
            return PathValidationResult(
                is_valid=False,
                error_message=f"Complete path exceeds {_MAX_PATH_LENGTH} characters (length: {len(path)})",
            )

        # Check each folder name length
        parts = path.split("/")
        for part in parts:
            if len(part) > _MAX_FOLDER_NAME_LENGTH:
                return PathValidationResult(
                    is_valid=False,
                    error_message=f"Folder name exceeds {_MAX_FOLDER_NAME_LENGTH} characters: '{part[:50]}...'",
                )

        return PathValidationResult(is_valid=True)

    @staticmethod
    def sanitize_device_name(name: str) -> str:
        """Sanitize a device name to only contain safe filesystem characters.

        Rules:
        - Only keep letters (a-z, A-Z), digits (0-9), underscores (_), and hyphens (-)
        - Replace all other characters with underscore (_)
        - Collapse consecutive underscores into one
        - Strip leading/trailing underscores

        Args:
            name: The raw device name string.

        Returns:
            The sanitized device name. Returns 'unknown_device' if the result
            would be empty.
        """
        # Replace all non-allowed characters with underscore
        sanitized = _DEVICE_NAME_ALLOWED.sub("_", name)

        # Collapse consecutive underscores
        sanitized = _CONSECUTIVE_UNDERSCORES.sub("_", sanitized)

        # Strip leading/trailing underscores
        sanitized = sanitized.strip("_")

        # Return a default if empty
        if not sanitized:
            return "unknown_device"

        return sanitized

    @staticmethod
    def _extract_year_month(file_metadata: FileMetadata) -> str:
        """Extract year/month directory path from file metadata.

        Priority:
        1. EXIF capture time
        2. File creation time
        3. "unknown_date" fallback

        Args:
            file_metadata: The file metadata containing timestamps.

        Returns:
            A string like "2026/03" or "unknown_date".
        """
        dt = file_metadata.exif_time or file_metadata.file_created_time

        if dt is not None:
            return f"{dt.year:04d}/{dt.month:02d}"

        return "unknown_date"
