"""Tests for effective_file_status — hardening against zombie records.

An "active but physically in .trash" record (deleted_at/purged_at NULL, yet
file_path under .trash) is contradictory legacy data from an old re-backup bug.
It must be bucketed as trashed so it does not inflate backed_up counts (which
made deleted folders keep showing a file count).
"""

from app.services.file_browse_service import derive_file_status, effective_file_status

ACTIVE_NORMAL = "/srv/storage/huo/2602DPT53G/DCIM/Camera/a.jpg"
ACTIVE_IN_TRASH = "/srv/storage/huo/.trash/2602DPT53G/DCIM/Camera/a.jpg"


def test_active_normal_path_is_backed_up():
    assert effective_file_status(None, None, ACTIVE_NORMAL) == "backed_up"


def test_active_but_in_trash_is_treated_as_trashed():
    # The zombie case: lifecycle says active, physical file is in the recycle bin.
    assert derive_file_status(None, None) == "backed_up"  # raw is misleading
    assert effective_file_status(None, None, ACTIVE_IN_TRASH) == "trashed"


def test_normal_trashed_unaffected():
    assert effective_file_status("2024-01-01T00:00:00", None, ACTIVE_IN_TRASH) == "trashed"


def test_normal_purged_unaffected():
    assert effective_file_status("2024-01-01T00:00:00", "2024-01-02T00:00:00", ACTIVE_IN_TRASH) == "purged"
    assert effective_file_status(None, "2024-01-02T00:00:00", ACTIVE_IN_TRASH) == "purged"


def test_none_path_falls_back_to_lifecycle():
    assert effective_file_status(None, None, None) == "backed_up"
