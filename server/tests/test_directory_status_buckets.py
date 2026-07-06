"""Property-based tests for directory status bucketing (Browse_Directory_Aggregation).

Feature: local-folder-backup-status, Property 2: 云端目录按状态分桶正确、互斥且守恒

These tests target the pure status derivation function ``derive_file_status`` and a
pure bucketing aggregation over a list of file rows (each row carrying nullable
``deleted_at`` / ``purged_at`` columns). They verify that every file is counted in
exactly one of the three buckets {backed_up, trashed, purged}, that all three counts
are non-negative, and that the three bucket counts sum to the directory's total file
count (``file_count``).

Validates: Requirements 3.1, 3.2, 2.3
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from hypothesis import given, settings
from hypothesis import strategies as st

from app.services.file_browse_service import derive_file_status


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# A single lifecycle timestamp column: either NULL (None) or a sample ISO string.
# Using a couple of distinct sample values keeps generation cheap while still
# exercising the "non-null" branch of the derivation.
_nullable_timestamp = st.one_of(
    st.none(),
    st.sampled_from(
        [
            "2024-01-01T00:00:00",
            "2023-06-15T12:30:45",
            "2025-12-31T23:59:59",
        ]
    ),
)


@dataclass
class _FileRow:
    """Minimal file row carrying only the lifecycle columns under test."""

    deleted_at: Optional[str]
    purged_at: Optional[str]


# A file row: (deleted_at, purged_at) — covers all-null, partial-null, and
# both-non-null boundaries via the nullable timestamp strategy.
_file_row = st.builds(_FileRow, deleted_at=_nullable_timestamp, purged_at=_nullable_timestamp)

# A list of file rows belonging to a single directory (empty list included).
_file_rows = st.lists(_file_row, min_size=0, max_size=50)


# ---------------------------------------------------------------------------
# Pure bucketing aggregation (mirrors list_directory's per-subdir accumulation)
# ---------------------------------------------------------------------------

@dataclass
class _Buckets:
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0
    file_count: int = 0


def _bucket_rows(rows: list[_FileRow]) -> _Buckets:
    """Pure aggregation: bucket each row by derive_file_status.

    Mirrors the per-subdirectory accumulation performed in
    ``FileBrowseService.list_directory``.
    """
    buckets = _Buckets()
    for row in rows:
        buckets.file_count += 1
        status = derive_file_status(row.deleted_at, row.purged_at)
        if status == "purged":
            buckets.purged_count += 1
        elif status == "trashed":
            buckets.trashed_count += 1
        else:
            buckets.backed_up_count += 1
    return buckets


# ---------------------------------------------------------------------------
# Property 2: 云端目录按状态分桶正确、互斥且守恒
# ---------------------------------------------------------------------------

@given(deleted_at=_nullable_timestamp, purged_at=_nullable_timestamp)
@settings(max_examples=200)
def test_derive_file_status_precedence_and_exclusivity(deleted_at, purged_at):
    """Feature: local-folder-backup-status, Property 2: 云端目录按状态分桶正确、互斥且守恒

    derive_file_status returns exactly one of the three buckets, honoring precedence:
    - purged_at not None  -> "purged" (regardless of deleted_at)
    - deleted_at not None and purged_at None -> "trashed"
    - both None -> "backed_up"

    Validates: Requirements 3.1, 3.2, 2.3
    """
    status = derive_file_status(deleted_at, purged_at)

    # Result is always exactly one of the three buckets (mutual exclusivity by construction).
    assert status in {"backed_up", "trashed", "purged"}

    # Precedence rules.
    if purged_at is not None:
        assert status == "purged"
    elif deleted_at is not None:
        assert status == "trashed"
    else:
        assert status == "backed_up"


@given(rows=_file_rows)
@settings(max_examples=200)
def test_directory_bucketing_conservation_and_non_negativity(rows):
    """Feature: local-folder-backup-status, Property 2: 云端目录按状态分桶正确、互斥且守恒

    For any list of file rows belonging to a directory:
    - each file is counted in exactly one bucket (conservation),
    - all three bucket counts are non-negative integers,
    - backed_up_count + trashed_count + purged_count == file_count (total rows).

    Validates: Requirements 3.1, 3.2, 2.3
    """
    buckets = _bucket_rows(rows)

    # file_count reflects the directory's total number of files.
    assert buckets.file_count == len(rows)

    # Non-negativity of each bucket count.
    assert buckets.backed_up_count >= 0
    assert buckets.trashed_count >= 0
    assert buckets.purged_count >= 0

    # Conservation: the three buckets sum exactly to the directory file total.
    assert (
        buckets.backed_up_count + buckets.trashed_count + buckets.purged_count
        == buckets.file_count
    )

    # Cross-check exclusivity: independently recompute expected bucket for each row
    # and confirm the aggregate matches (each row lands in one and only one bucket).
    expected_backed_up = sum(
        1 for r in rows if derive_file_status(r.deleted_at, r.purged_at) == "backed_up"
    )
    expected_trashed = sum(
        1 for r in rows if derive_file_status(r.deleted_at, r.purged_at) == "trashed"
    )
    expected_purged = sum(
        1 for r in rows if derive_file_status(r.deleted_at, r.purged_at) == "purged"
    )
    assert buckets.backed_up_count == expected_backed_up
    assert buckets.trashed_count == expected_trashed
    assert buckets.purged_count == expected_purged
