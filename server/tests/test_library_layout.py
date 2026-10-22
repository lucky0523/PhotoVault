"""Unit tests for the shared library path helpers (F1/F2 contracts)."""

import sqlite3
from pathlib import PurePosixPath

import pytest

from app.models.storage import FileMetadata, StoragePolicy
from app.services.library_layout import (
    original_path_for,
    path_prefix_range,
    path_prefix_sql,
    trash_path_for,
    trash_root,
    user_root,
)
from app.services.storage_path_engine import StoragePathEngine, device_dir_name

ROOT = "/srv/media"

SIBLINGS = [
    "a_b/1.jpg", "axb/2.jpg", "A_B/3.jpg", "a%b/4.jpg", "aXb/5.jpg", "a_b2/6.jpg",
    "a_b/sub/7.jpg", "截图_1/8.jpg", "截图x1/9.jpg", "Camera/x.jpg", "camera/y.jpg",
    "📷/z.jpg",
]


@pytest.fixture
def conn():
    c = sqlite3.connect(":memory:")
    c.execute("CREATE TABLE t (file_path TEXT NOT NULL)")
    c.executemany("INSERT INTO t VALUES (?)", [(f"{ROOT}/alice/P/{p}",) for p in SIBLINGS])
    yield c
    c.close()


def _match(conn, prefix, direct=False):
    sql, params = path_prefix_sql("file_path", prefix, direct_children_only=direct)
    rows = conn.execute(f"SELECT file_path FROM t WHERE {sql}", params).fetchall()
    return sorted(r[0][len(prefix):] for r in rows)


@pytest.mark.parametrize(
    "folder, recursive, direct",
    [
        ("a_b", ["1.jpg", "sub/7.jpg"], ["1.jpg"]),
        ("a%b", ["4.jpg"], ["4.jpg"]),
        ("axb", ["2.jpg"], ["2.jpg"]),
        ("A_B", ["3.jpg"], ["3.jpg"]),
        ("Camera", ["x.jpg"], ["x.jpg"]),
        ("camera", ["y.jpg"], ["y.jpg"]),
        ("截图_1", ["8.jpg"], ["8.jpg"]),
        ("📷", ["z.jpg"], ["z.jpg"]),
    ],
)
def test_prefix_matches_only_its_own_subtree(conn, folder, recursive, direct):
    prefix = f"{ROOT}/alice/P/{folder}/"
    assert _match(conn, prefix) == recursive
    assert _match(conn, prefix, direct=True) == direct


def test_prefix_direct_children_of_parent(conn):
    assert _match(conn, f"{ROOT}/alice/P/", direct=True) == []
    assert len(_match(conn, f"{ROOT}/alice/P/")) == len(SIBLINGS)


def test_prefix_range_and_sql_validation():
    assert path_prefix_range("/a/b/") == ("/a/b/", "/a/b0")
    with pytest.raises(ValueError):
        path_prefix_range("/a/b")
    with pytest.raises(ValueError):
        path_prefix_sql("file_path; DROP TABLE t", "/a/")
    sql, params = path_prefix_sql("fr.file_path", "/a/", direct_children_only=True)
    assert sql == (
        "fr.file_path >= ? AND fr.file_path < ? AND instr(substr(fr.file_path, ?), '/') = 0"
    )
    assert params == ["/a/", "/a0", 4]


def _resolved(device, folder, *, custom=None, name="a.jpg"):
    policy = StoragePolicy(use_custom_path=custom is not None, custom_path=custom)
    return StoragePathEngine.resolve_path(ROOT, "alice", device, folder, policy, FileMetadata()) + name


@pytest.mark.parametrize("device", ["Pixel 9 Pro", "小米 14 Ultra", "华为", "Pixel9Pro"])
@pytest.mark.parametrize("folder", ["DCIM/Camera", "Pictures/a_b", "Pictures/100%", "DCIM/相机"])
def test_trash_round_trip_in_library(device, folder):
    path = _resolved(device, folder)
    rel = PurePosixPath(path).relative_to(user_root(ROOT, "alice"))
    trashed = trash_path_for(ROOT, "alice", path)
    assert trashed == f"{trash_root(ROOT, 'alice')}/{rel}"
    assert original_path_for(ROOT, "alice", trashed) == path
    # Already trashed: nothing to move.
    assert trash_path_for(ROOT, "alice", trashed) is None


def test_trash_round_trip_custom_paths():
    outside = _resolved("Pixel 9 Pro", "DCIM/Camera", custom="/mnt/lib")
    assert outside == "/mnt/lib/DCIM/Camera/a.jpg"
    trashed = trash_path_for(ROOT, "alice", outside)
    assert trashed == f"{ROOT}/alice/.trash/.external/mnt/lib/DCIM/Camera/a.jpg"
    assert original_path_for(ROOT, "alice", trashed) == outside

    at_root = _resolved("Pixel 9 Pro", "", custom=f"{ROOT}/alice", name="x.jpg")
    assert at_root == f"{ROOT}/alice/x.jpg"
    assert trash_path_for(ROOT, "alice", at_root) == f"{ROOT}/alice/.trash/x.jpg"
    assert original_path_for(ROOT, "alice", f"{ROOT}/alice/.trash/x.jpg") == at_root

    # An in-library top-level ".external" never collides with the namespace.
    odd = f"{ROOT}/alice/.external/x.jpg"
    odd_trashed = trash_path_for(ROOT, "alice", odd)
    assert odd_trashed == f"{ROOT}/alice/.trash/.external{odd}"
    assert original_path_for(ROOT, "alice", odd_trashed) == odd


def test_original_path_outside_trash_is_none_and_bad_paths_raise():
    assert original_path_for(ROOT, "alice", f"{ROOT}/alice/P/a.jpg") is None
    assert original_path_for(ROOT, "alice", f"{ROOT}/bob/.trash/P/a.jpg") is None
    for bad in ("relative/a.jpg", f"{ROOT}/alice/../bob/a.jpg"):
        with pytest.raises(ValueError):
            trash_path_for(ROOT, "alice", bad)
        with pytest.raises(ValueError):
            original_path_for(ROOT, "alice", bad)


def test_old_trash_layout_is_identical_for_device_names_without_spaces():
    """HEAD wrote .trash/{device}/{path relative to user_root/device}."""
    path = _resolved("Pixel9Pro", "DCIM/Camera")
    rel = PurePosixPath(path).relative_to(f"{ROOT}/alice/Pixel9Pro")
    assert trash_path_for(ROOT, "alice", path) == f"{ROOT}/alice/.trash/Pixel9Pro/{rel}"


def test_device_dir_name_matches_resolve_path():
    assert device_dir_name("Pixel 9 Pro") == "Pixel_9_Pro"
    for device in ("Pixel 9 Pro", "小米 14 Ultra", "Pixel9Pro"):
        segment = PurePosixPath(_resolved(device, "DCIM")).relative_to(f"{ROOT}/alice").parts[0]
        assert segment == device_dir_name(device)
