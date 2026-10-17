"""Structural guards for file versioning (design 7.2 and 8, acceptance 12, 16).

History is isolated by construction: only a whitelist of modules may mention
``file_versions``. Duplicate detection (deduplication_service, api/backup,
upload_service) is deliberately not on it (plan B: history never takes part
in duplicate checks). ``server/scripts`` and ``server/tests`` are not scanned.
"""

from __future__ import annotations

from pathlib import Path

APP_ROOT = Path(__file__).resolve().parents[1] / "app"

FILE_VERSIONS_WHITELIST = {
    "core/database.py",
    "services/version_service.py",
    "api/admin.py",
    "services/file_browse_service.py",
    "api/server.py",
}


def _sources():
    for path in sorted(APP_ROOT.rglob("*.py")):
        yield path.relative_to(APP_ROOT).as_posix(), path.read_text(encoding="utf-8")


def test_file_versions_is_only_used_by_the_whitelist():
    users = {rel for rel, text in _sources() if "file_versions" in text}
    assert users == FILE_VERSIONS_WHITELIST
    for dedup_path in (
        "services/deduplication_service.py",
        "api/backup.py",
        "services/upload_service.py",
        "services/background_tasks.py",
    ):
        assert dedup_path not in users


def test_no_travel_special_case_in_app():
    """Acceptance 12: no special handling of travel.info in server/app."""
    hits = [rel for rel, text in _sources() if "travel" in text.lower()]
    assert hits == []
