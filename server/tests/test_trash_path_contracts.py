"""Contract tests: paths produced by the real upload flow, consumed by browse,
delete, trash, restore and purge (audit findings F1, F2, F6).

Paths always come from the real producers (see tests/contract_helpers.py), and
the values are chosen to hit the failure modes: device names with spaces and
Chinese characters, directory names with ``_`` and ``%``, case-variant
siblings, custom storage paths and a file directly under the user root.
"""

import os
import shutil
from pathlib import Path, PurePosixPath

import pytest

from app.core.config import reset_settings
from app.services import file_browse_service as fbs
from app.services.background_tasks import _run_trash_purge
from app.services.deduplication_service import DeduplicationService
from app.services.storage_path_engine import device_dir_name
from tests.contract_helpers import (
    backdate_deleted,
    close_presence_fds,
    fetch,
    jpeg_bytes,
    make_library,
    upload,
)


@pytest.fixture
async def lib(tmp_path, monkeypatch):
    lib = await make_library(tmp_path, monkeypatch)
    try:
        yield lib
    finally:
        await lib.db.close()
        close_presence_fds()
        reset_settings()


def _trash(lib, rel: str) -> str:
    return f"{lib.media_root}/alice/.trash/{rel}"


# ---------------------------------------------------------------------------
# F1: directory prefixes are literal (no LIKE wildcards, case-sensitive)
# ---------------------------------------------------------------------------

F1_DIRS = [
    "a_b", "axb", "A_B", "a%b", "a_b2", "a_b/sub", "Camera", "camera", "截图_1", "截图x1",
]


async def _f1_library(lib) -> dict:
    rows = {}
    for seed, folder in enumerate(F1_DIRS, 1):
        name = f"f{seed}.jpg"  # distinct names: APFS folds Camera/camera
        rows[folder] = await upload(
            lib, 1, "alice", device="Pixel9", source_folder=f"Pictures/{folder}",
            name=name, seed=seed,
        )
    return rows


async def test_list_directory_matches_prefix_literally(lib):
    await _f1_library(lib)
    listing = await lib.browse.list_directory(1, "Pixel9/Pictures/a_b")
    assert listing.total_files == 1
    assert [f.file_name for f in listing.files] == ["f1.jpg"]
    assert [(d.name, d.file_count) for d in listing.directories] == [("sub", 1)]

    listing = await lib.browse.list_directory(1, "Pixel9/Pictures")
    counts = {d.name: d.file_count for d in listing.directories}
    assert counts == {
        "a_b": 2, "axb": 1, "A_B": 1, "a%b": 1, "a_b2": 1,
        "Camera": 1, "camera": 1, "截图_1": 1, "截图x1": 1,
    }
    assert listing.total_files == 0


@pytest.mark.parametrize(
    "target, expected",
    [
        ("a_b", {"a_b", "a_b/sub"}),
        ("a%b", {"a%b"}),
        ("Camera", {"Camera"}),
        ("截图_1", {"截图_1"}),
    ],
)
async def test_delete_directory_only_trashes_its_own_subtree(lib, target, expected):
    rows = await _f1_library(lib)
    count, message = await lib.browse.delete_directory(1, f"Pixel9/Pictures/{target}")
    assert count == len(expected)
    assert message == f"Moved {len(expected)} file(s) to trash"
    for folder, before in rows.items():
        after = await fetch(lib, before["id"])
        if folder in expected:
            assert after["deleted_at"] is not None
            rel = PurePosixPath(before["file_path"]).relative_to(f"{lib.media_root}/alice")
            assert after["file_path"] == _trash(lib, str(rel))
            assert os.path.exists(after["file_path"])
        else:
            assert after["deleted_at"] is None, folder
            assert after["file_path"] == before["file_path"]
            assert os.path.exists(after["file_path"]), folder

    listing = await lib.browse.list_directory(1, "Pixel9/Pictures")
    by_name = {d.name: d for d in listing.directories}
    top = target.split("/")[0]
    assert by_name[top].trashed_count == len(expected)
    for name, info in by_name.items():
        if name != top:
            assert info.trashed_count == 0 and info.backed_up_count == info.file_count, name


# ---------------------------------------------------------------------------
# F2: trash mirrors the path relative to the user root
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("device", ["Pixel 9 Pro", "小米 14 Ultra"])
async def test_delete_and_restore_file_for_device_name_with_spaces(lib, device):
    row = await upload(lib, 1, "alice", device=device, source_folder="DCIM/相机", name="a.jpg", seed=1)
    dir_name = device_dir_name(device)
    original = row["file_path"]
    assert original == f"{lib.media_root}/alice/{dir_name}/DCIM/相机/a.jpg"

    assert await lib.browse.delete_file(1, row["id"]) == (True, "File moved to trash")
    trashed = await fetch(lib, row["id"])
    assert trashed["file_path"] == _trash(lib, f"{dir_name}/DCIM/相机/a.jpg")
    assert os.path.exists(trashed["file_path"]) and not os.path.exists(original)
    assert not os.path.exists(_trash(lib, device))  # no raw-device dir

    items, total = await lib.browse.list_trash(1)
    assert total == 1 and items[0].display_path == f"/{dir_name}/DCIM/相机/a.jpg"

    assert await lib.browse.restore_file(1, row["id"]) == (True, "File restored")
    restored = await fetch(lib, row["id"])
    assert restored["file_path"] == original and restored["deleted_at"] is None
    assert os.path.exists(original)


async def test_delete_directory_and_restore_batch_with_spaces(lib):
    rows = [
        await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder=folder, name=f"{i}.jpg", seed=i)
        for i, folder in enumerate(["DCIM/Camera", "DCIM/相机", "DCIM/Camera/sub"], 1)
    ]
    count, _ = await lib.browse.delete_directory(1, "Pixel_9_Pro/DCIM")
    assert count == 3
    trashed = [await fetch(lib, r["id"]) for r in rows]
    batch = trashed[0]["deleted_batch_id"]
    for before, after in zip(rows, trashed):
        assert after["file_path"].startswith(_trash(lib, "Pixel_9_Pro/DCIM/"))
        assert os.path.exists(after["file_path"]) and not os.path.exists(before["file_path"])

    assert await lib.browse.restore_batch(1, batch) == (3, "Restored 3 file(s)")
    for before in rows:
        after = await fetch(lib, before["id"])
        assert after["file_path"] == before["file_path"] and after["deleted_at"] is None
        assert os.path.exists(after["file_path"])


async def test_purge_and_auto_purge_remove_the_trashed_copy(lib):
    a = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    b = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="b.jpg", seed=2)
    for row in (a, b):
        assert (await lib.browse.delete_file(1, row["id"]))[0]

    trashed_a = (await fetch(lib, a["id"]))["file_path"]
    assert (await lib.browse.purge_file(1, a["id"]))[0]
    purged = await fetch(lib, a["id"])
    assert purged["purged_at"] is not None and not os.path.exists(trashed_a)
    assert os.path.isdir(f"{lib.media_root}/alice/.trash")

    trashed_b = (await fetch(lib, b["id"]))["file_path"]
    await backdate_deleted(lib, b["id"])
    assert await _run_trash_purge(lib.db_path, lib.media_root, 30) == 1
    assert (await fetch(lib, b["id"]))["purged_at"] is not None
    assert not os.path.exists(trashed_b)
    # Empty trash sub-directories go, the trash root stays.
    assert os.path.isdir(f"{lib.media_root}/alice/.trash")
    assert not os.path.exists(_trash(lib, "Pixel_9_Pro"))


async def test_device_stats_path_is_the_stored_directory(lib):
    await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    stats = await lib.browse.get_device_stats(1)
    assert [(s.name, s.path, s.backed_up_count) for s in stats] == [("Pixel 9 Pro", "Pixel_9_Pro", 1)]
    listing = await lib.browse.list_directory(1, stats[0].path)
    assert [d.name for d in listing.directories] == ["DCIM"]


# ---------------------------------------------------------------------------
# Compatibility with trash contents and records written by HEAD
# ---------------------------------------------------------------------------


async def _put_in_old_layout(lib, row, batch_id: str) -> str:
    """Trash a record exactly like HEAD did: .trash/{device}/{rel to device dir}."""
    device_root = f"{lib.media_root}/alice/{row['device_name']}"
    rel = PurePosixPath(row["file_path"]).relative_to(device_root)
    old_path = f"{lib.media_root}/alice/.trash/{row['device_name']}/{rel}"
    os.makedirs(os.path.dirname(old_path), exist_ok=True)
    shutil.move(row["file_path"], old_path)
    await lib.db.execute(
        "UPDATE file_records SET deleted_at = '2026-10-01T00:00:00+00:00', deleted_batch_id = ?, "
        "file_path = ? WHERE id = ?",
        (batch_id, old_path, row["id"]),
    )
    await lib.db.commit()
    return old_path


async def test_new_trash_path_equals_old_layout_without_spaces(lib):
    row = await upload(lib, 1, "alice", device="Pixel9Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    rel = PurePosixPath(row["file_path"]).relative_to(f"{lib.media_root}/alice/Pixel9Pro")
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    assert (await fetch(lib, row["id"]))["file_path"] == _trash(lib, f"Pixel9Pro/{rel}")


async def test_old_layout_items_restore_and_purge(lib):
    rows = [
        await upload(lib, 1, "alice", device="Pixel9Pro", source_folder="DCIM/Camera", name=f"{i}.jpg", seed=i)
        for i in range(1, 4)
    ]
    await _put_in_old_layout(lib, rows[0], "b-single")
    assert await lib.browse.restore_file(1, rows[0]["id"]) == (True, "File restored")
    after = await fetch(lib, rows[0]["id"])
    assert after["file_path"] == rows[0]["file_path"] and os.path.exists(after["file_path"])

    await _put_in_old_layout(lib, rows[1], "b-batch")
    assert await lib.browse.restore_batch(1, "b-batch") == (1, "Restored 1 file(s)")
    assert (await fetch(lib, rows[1]["id"]))["file_path"] == rows[1]["file_path"]
    assert os.path.exists(rows[1]["file_path"])

    old_path = await _put_in_old_layout(lib, rows[2], "b-purge")
    assert (await lib.browse.purge_file(1, rows[2]["id"]))[0]
    assert not os.path.exists(old_path)


async def _fake_delete(lib, file_id: int) -> None:
    """What HEAD did for device names with spaces: deleted_at only."""
    await lib.db.execute(
        "UPDATE file_records SET deleted_at = '2026-10-01T00:00:00+00:00', deleted_batch_id = 'fake' "
        "WHERE id = ?",
        (file_id,),
    )
    await lib.db.commit()


async def test_historical_fake_deleted_records_restore_and_purge_in_place(lib):
    a = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    b = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="b.jpg", seed=2)
    keep = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="c.jpg", seed=3)
    await _fake_delete(lib, a["id"])
    await _fake_delete(lib, b["id"])

    items, total = await lib.browse.list_trash(1)
    assert total == 2
    assert {i.display_path for i in items} == {
        "/Pixel_9_Pro/DCIM/Camera/a.jpg", "/Pixel_9_Pro/DCIM/Camera/b.jpg",
    }

    assert await lib.browse.restore_file(1, a["id"]) == (True, "File restored")
    after = await fetch(lib, a["id"])
    assert after["file_path"] == a["file_path"] and after["deleted_at"] is None
    assert os.path.exists(a["file_path"])

    assert (await lib.browse.purge_file(1, b["id"]))[0]
    assert (await fetch(lib, b["id"]))["purged_at"] is not None
    assert not os.path.exists(b["file_path"])
    assert os.path.exists(a["file_path"]) and os.path.exists(keep["file_path"])


async def test_purge_in_place_keeps_a_file_another_live_record_uses(lib):
    a = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    await _fake_delete(lib, a["id"])
    await DeduplicationService(lib.db).register_file(
        user_id=1, file_hash="other-hash", file_path=a["file_path"], original_path="/x/a.jpg",
        device_name="Pixel 9 Pro", file_size=1, file_name="a.jpg",
    )
    assert (await lib.browse.purge_file(1, a["id"]))[0]
    assert (await fetch(lib, a["id"]))["purged_at"] is not None
    assert os.path.exists(a["file_path"])


async def test_active_record_already_under_trash_is_not_moved_again(lib):
    row = await upload(lib, 1, "alice", device="Pixel9Pro", source_folder="DCIM", name="a.jpg", seed=1)
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    in_trash = (await fetch(lib, row["id"]))["file_path"]
    await lib.db.execute("UPDATE file_records SET deleted_at = NULL WHERE id = ?", (row["id"],))
    await lib.db.commit()

    assert await lib.browse.delete_file(1, row["id"]) == (True, "File moved to trash")
    after = await fetch(lib, row["id"])
    assert after["deleted_at"] is not None and after["file_path"] == in_trash
    assert os.path.exists(in_trash)
    assert not os.path.exists(_trash(lib, ".trash"))


# ---------------------------------------------------------------------------
# Custom storage paths
# ---------------------------------------------------------------------------


async def test_custom_path_outside_media_root(lib):
    custom = str(lib.base / "custom_lib")
    row = await upload(
        lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg",
        seed=1, custom_path=custom,
    )
    original = row["file_path"]
    assert original == f"{custom}/DCIM/Camera/a.jpg"
    before = await lib.browse.list_directory(1, "")

    assert await lib.browse.delete_file(1, row["id"]) == (True, "File moved to trash")
    trashed = await fetch(lib, row["id"])
    assert trashed["file_path"] == _trash(lib, ".external/" + original.lstrip("/"))
    assert os.path.exists(trashed["file_path"]) and not os.path.exists(original)

    items, _ = await lib.browse.list_trash(1)
    assert items[0].display_path == original
    after = await lib.browse.list_directory(1, "")
    assert [d.name for d in after.directories] == [d.name for d in before.directories]
    assert ".external" not in [d.name for d in after.directories]
    assert after.total_files == before.total_files

    assert await lib.browse.restore_file(1, row["id"]) == (True, "File restored")
    assert (await fetch(lib, row["id"]))["file_path"] == original
    assert os.path.exists(original)

    assert (await lib.browse.delete_file(1, row["id"]))[0]
    trashed_path = (await fetch(lib, row["id"]))["file_path"]
    assert (await lib.browse.purge_file(1, row["id"]))[0]
    assert not os.path.exists(trashed_path) and not os.path.exists(original)
    assert os.path.isdir(custom)
    assert os.path.isdir(f"{lib.media_root}/alice/.trash")


async def test_file_directly_under_user_root(lib):
    """F6 shape: custom path == user root, no source folder."""
    row = await upload(
        lib, 1, "alice", device="Pixel 9 Pro", source_folder="", name="x.jpg",
        seed=1, custom_path=f"{lib.media_root}/alice",
    )
    assert row["file_path"] == f"{lib.media_root}/alice/x.jpg"
    assert (await lib.browse.list_directory(1, "")).total_files == 1

    assert (await lib.browse.delete_file(1, row["id"]))[0]
    assert (await fetch(lib, row["id"]))["file_path"] == _trash(lib, "x.jpg")
    items, total = await lib.browse.list_trash(1)
    assert total == 1 and items[0].display_path == "/x.jpg"

    assert (await lib.browse.restore_file(1, row["id"]))[0]
    assert (await fetch(lib, row["id"]))["file_path"] == row["file_path"]
    assert os.path.exists(row["file_path"])


# ---------------------------------------------------------------------------
# Failures are reported, never silent
# ---------------------------------------------------------------------------


async def test_restore_never_overwrites_the_original_location(lib):
    old = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=1)
    assert (await lib.browse.delete_file(1, old["id"]))[0]
    new = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=2)
    assert new["file_path"] == old["file_path"]

    ok, message = await lib.browse.restore_file(1, old["id"])
    assert not ok and message.startswith("Cannot restore:")
    trashed = await fetch(lib, old["id"])
    assert trashed["deleted_at"] is not None
    assert Path(trashed["file_path"]).read_bytes() == jpeg_bytes(1)
    assert Path(new["file_path"]).read_bytes() == jpeg_bytes(2)

    # The batch restore leaves it in the trash as well.
    assert await lib.browse.restore_batch(1, trashed["deleted_batch_id"]) == (0, "Restored 0 file(s)")
    assert (await fetch(lib, old["id"]))["deleted_at"] is not None


async def test_restore_batch_counts_only_restored_rows(lib):
    a = await upload(lib, 1, "alice", device="Pixel9", source_folder="DCIM", name="a.jpg", seed=1)
    b = await upload(lib, 1, "alice", device="Pixel9", source_folder="DCIM", name="b.jpg", seed=2)
    assert (await lib.browse.delete_directory(1, "Pixel9/DCIM"))[0] == 2
    Path(b["file_path"]).write_bytes(b"occupied")
    batch = (await fetch(lib, a["id"]))["deleted_batch_id"]

    assert await lib.browse.restore_batch(1, batch) == (1, "Restored 1 file(s)")
    assert (await fetch(lib, a["id"]))["deleted_at"] is None
    still = await fetch(lib, b["id"])
    assert still["deleted_at"] is not None and "/.trash/" in still["file_path"]
    assert Path(still["file_path"]).read_bytes() == jpeg_bytes(2)
    assert Path(b["file_path"]).read_bytes() == b"occupied"


async def test_delete_refuses_a_trash_destination_owned_by_another_record(lib):
    first = await upload(lib, 1, "alice", device="Pixel9", source_folder="DCIM", name="a.jpg", seed=1)
    assert (await lib.browse.delete_file(1, first["id"]))[0]
    second = await upload(lib, 1, "alice", device="Pixel9", source_folder="DCIM", name="a.jpg", seed=2)
    assert second["file_path"] == first["file_path"]

    ok, message = await lib.browse.delete_file(1, second["id"])
    assert not ok and message.startswith("Failed to move file to trash:")
    after = await fetch(lib, second["id"])
    assert after["deleted_at"] is None and after["file_path"] == second["file_path"]
    assert Path(second["file_path"]).read_bytes() == jpeg_bytes(2)
    trashed = (await fetch(lib, first["id"]))["file_path"]
    assert Path(trashed).read_bytes() == jpeg_bytes(1)


async def test_failed_move_leaves_the_file_and_record_unchanged(lib, monkeypatch):
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=1)
    real_move = shutil.move
    partial = []

    def failing_move(src, dst):
        # Like a cross-volume copy that dies half way.
        Path(dst).write_bytes(b"partial")
        partial.append(dst)
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(fbs.shutil, "move", failing_move)
    ok, message = await lib.browse.delete_file(1, row["id"])
    monkeypatch.setattr(fbs.shutil, "move", real_move)

    assert not ok and "No space left on device" in message
    after = await fetch(lib, row["id"])
    assert after["deleted_at"] is None and after["file_path"] == row["file_path"]
    assert Path(row["file_path"]).read_bytes() == jpeg_bytes(1)
    assert partial and not os.path.exists(partial[0])


async def test_failed_move_inside_delete_directory_is_reported(lib, monkeypatch):
    a = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=1)
    b = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="b.jpg", seed=2)
    real_move = shutil.move

    def selective_move(src, dst):
        if src.endswith("/b.jpg"):
            raise OSError(13, "Permission denied")
        return real_move(src, dst)

    monkeypatch.setattr(fbs.shutil, "move", selective_move)
    count, message = await lib.browse.delete_directory(1, "Pixel_9_Pro/DCIM")
    assert count == 1
    assert message == "Moved 1 file(s) to trash; 1 file(s) could not be moved and were left unchanged"
    assert (await fetch(lib, a["id"]))["deleted_at"] is not None
    kept = await fetch(lib, b["id"])
    assert kept["deleted_at"] is None and kept["file_path"] == b["file_path"]
    assert os.path.exists(b["file_path"])

    monkeypatch.setattr(fbs.shutil, "move", lambda src, dst: (_ for _ in ()).throw(OSError(13, "denied")))
    assert await lib.browse.delete_directory(1, "Pixel_9_Pro/DCIM") == (0, "Could not move 1 file(s) to trash")
    assert (await fetch(lib, b["id"]))["deleted_at"] is None
