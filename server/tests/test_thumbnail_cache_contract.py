"""Thumbnail cache contract: one generator/parser for cache names, and caches
of permanently deleted files are neither served nor kept (audit finding F3).

Cache files are always created by the real ``get_thumbnail`` (or under the
name ``thumbnail_cache_name`` generates) and consumed by the real
``_run_thumbnail_cleanup``.
"""

import logging

import pytest

from app.core.config import reset_settings
from app.services.background_tasks import _run_thumbnail_cleanup, _run_trash_purge
from app.services.deduplication_service import DeduplicationService
from app.services.file_browse_service import FileBrowseService, ThumbnailName
from tests.contract_helpers import (
    backdate_deleted,
    close_presence_fds,
    fetch,
    jpeg_bytes,
    make_library,
    upload,
)

SIZES = list(FileBrowseService.THUMBNAIL_SIZES)
ZERO = {"orphaned": 0, "expired": 0, "size_limited": 0, "obsolete": 0}


# ---------------------------------------------------------------------------
# Name generator / parser
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("size", SIZES)
@pytest.mark.parametrize("file_hash", ["a" * 64, "odd_hash_with_underscores"])
def test_cache_name_round_trip(file_hash, size):
    name = FileBrowseService.thumbnail_cache_name(file_hash, size)
    assert name.endswith(FileBrowseService.THUMBNAIL_EXT)
    assert FileBrowseService.parse_thumbnail_name(name) == ThumbnailName(
        file_hash, size, FileBrowseService.THUMBNAIL_CACHE_VERSION
    )


def test_parse_legacy_and_foreign_names():
    h = "b" * 64
    assert FileBrowseService.parse_thumbnail_name(f"{h}_small.jpg") == ThumbnailName(h, "small", None)
    assert FileBrowseService.parse_thumbnail_name(f"{h}_medium_v1.jpg") == ThumbnailName(h, "medium", "v1")
    for foreign in (f"{h}_small_v2.png", f"{h}_large_v2.jpg", "README.jpg", f"{h}.jpg"):
        assert FileBrowseService.parse_thumbnail_name(foreign) is None


def test_cache_name_rejects_unparseable_input():
    with pytest.raises(ValueError):
        FileBrowseService.thumbnail_cache_name("h", "large")
    with pytest.raises(ValueError):
        FileBrowseService.thumbnail_cache_name("h", "small", version="v_2")


# ---------------------------------------------------------------------------
# get_thumbnail -> cleanup / purge, end to end
# ---------------------------------------------------------------------------


@pytest.fixture
async def lib(tmp_path, monkeypatch):
    lib = await make_library(tmp_path, monkeypatch)
    try:
        yield lib
    finally:
        await lib.db.close()
        close_presence_fds()
        reset_settings()


def _cache_paths(lib, username, file_hash):
    cache_dir = FileBrowseService.thumbnail_cache_dir(lib.media_root, username)
    return [cache_dir / FileBrowseService.thumbnail_cache_name(file_hash, s) for s in SIZES]


async def _thumbs(lib, user_id, file_id):
    for size in SIZES:
        assert await lib.browse.get_thumbnail(user_id, file_id, size) is not None


async def test_generated_cache_is_kept_by_cleanup_also_while_trashed(lib):
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    await _thumbs(lib, 1, row["id"])
    paths = _cache_paths(lib, "alice", row["file_hash"])
    assert all(p.exists() for p in paths)

    assert await _run_thumbnail_cleanup(lib.db_path, lib.media_root) == ZERO
    assert all(p.exists() for p in paths)

    # Trashed files keep serving thumbnails (Web trash view) and keep caches.
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    await _thumbs(lib, 1, row["id"])
    assert await _run_thumbnail_cleanup(lib.db_path, lib.media_root) == ZERO
    assert all(p.exists() for p in paths)


async def test_purge_removes_cache_and_stops_serving(lib):
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    await _thumbs(lib, 1, row["id"])
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    assert (await lib.browse.purge_file(1, row["id"]))[0]

    assert not any(p.exists() for p in _cache_paths(lib, "alice", row["file_hash"]))
    for size in SIZES:
        assert await lib.browse.get_thumbnail(1, row["id"], size) is None


async def test_auto_purge_removes_cache(lib):
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM/Camera", name="a.jpg", seed=1)
    await _thumbs(lib, 1, row["id"])
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    await backdate_deleted(lib, row["id"])

    assert await _run_trash_purge(lib.db_path, lib.media_root, 30) == 1
    assert not any(p.exists() for p in _cache_paths(lib, "alice", row["file_hash"]))
    assert await lib.browse.get_thumbnail(1, row["id"]) is None


async def test_cached_bytes_of_a_purged_record_are_not_served(lib):
    """Even with a cache file present (left by an older server), purged means 404."""
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=1)
    data = await lib.browse.get_thumbnail(1, row["id"], "small")
    assert (await lib.browse.delete_file(1, row["id"]))[0]
    assert (await lib.browse.purge_file(1, row["id"]))[0]

    leftover = _cache_paths(lib, "alice", row["file_hash"])[0]
    leftover.parent.mkdir(parents=True, exist_ok=True)
    leftover.write_bytes(data)
    assert await lib.browse.get_thumbnail(1, row["id"], "small") is None

    result = await _run_thumbnail_cleanup(lib.db_path, lib.media_root)
    assert result == {**ZERO, "orphaned": 1}
    assert not leftover.exists()


async def test_shared_hash_keeps_cache_until_last_record_is_purged(lib):
    row = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", seed=1)
    ref_path = f"{lib.media_root}/alice/Pixel_9_Pro/Other/a.jpg"
    ref = await DeduplicationService(lib.db).create_reference(
        user_id=1, file_hash=row["file_hash"], target_path=ref_path, source_record_id=row["id"],
        original_path="/storage/emulated/0/Other/a.jpg", device_name="Pixel 9 Pro",
        file_size=row["file_size"], file_name="a.jpg",
    )
    await _thumbs(lib, 1, row["id"])
    paths = _cache_paths(lib, "alice", row["file_hash"])

    assert (await lib.browse.delete_file(1, row["id"]))[0]
    ok, message = await lib.browse.purge_file(1, row["id"])
    assert ok and "ownership transferred" in message
    assert all(p.exists() for p in paths)
    assert await lib.browse.get_thumbnail(1, ref.id, "small") is not None

    assert (await lib.browse.delete_file(1, ref.id))[0]
    assert (await lib.browse.purge_file(1, ref.id))[0]
    assert not any(p.exists() for p in paths)
    assert (await fetch(lib, ref.id))["purged_at"] is not None


async def test_cleanup_validity_is_per_user(lib):
    content = jpeg_bytes(7)
    alice = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", content=content)
    bob = await upload(lib, 2, "bob", device="小米 14 Ultra", source_folder="DCIM", name="a.jpg", content=content)
    assert alice["file_hash"] == bob["file_hash"]
    await _thumbs(lib, 1, alice["id"])
    await _thumbs(lib, 2, bob["id"])
    assert (await lib.browse.delete_file(1, alice["id"]))[0]
    assert (await lib.browse.purge_file(1, alice["id"]))[0]

    # Plant alice's cache again (as a pre-fix server would have left it).
    alice_paths = _cache_paths(lib, "alice", alice["file_hash"])
    for p in alice_paths:
        p.write_bytes(b"stale")
    bob_paths = _cache_paths(lib, "bob", bob["file_hash"])

    result = await _run_thumbnail_cleanup(lib.db_path, lib.media_root)
    assert result == {**ZERO, "orphaned": len(SIZES)}
    assert not any(p.exists() for p in alice_paths)
    assert all(p.exists() for p in bob_paths)


@pytest.mark.parametrize("username", ["a*", "[ab]lice", "?lice"])
async def test_purge_by_glob_metachar_user_keeps_other_users_caches(lib, username):
    """The cache dir is literal: purging user ``a*`` must not touch alice's cache."""
    await lib.db.execute("INSERT INTO users (id, username, password_hash) VALUES (3, ?, 'x')", (username,))
    await lib.db.commit()
    content = jpeg_bytes(11)
    alice = await upload(lib, 1, "alice", device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", content=content)
    other = await upload(lib, 3, username, device="Pixel 9 Pro", source_folder="DCIM", name="a.jpg", content=content)
    assert alice["file_hash"] == other["file_hash"]
    await _thumbs(lib, 1, alice["id"])
    await _thumbs(lib, 3, other["id"])
    alice_paths = _cache_paths(lib, "alice", alice["file_hash"])
    other_paths = _cache_paths(lib, username, other["file_hash"])
    assert all(p.exists() for p in alice_paths + other_paths)

    assert (await lib.browse.delete_file(3, other["id"]))[0]
    assert (await lib.browse.purge_file(3, other["id"]))[0]

    assert not any(p.exists() for p in other_paths)
    assert all(p.exists() for p in alice_paths)
    assert await lib.browse.get_thumbnail(1, alice["id"], "small") is not None


def test_remove_thumbnails_for_hash_takes_media_root_literally(tmp_path):
    """A media_root with glob metacharacters still finds (only) its own files."""
    h = "d" * 64
    media_root = str(tmp_path / "Photos [nas]")
    own = FileBrowseService.thumbnail_cache_dir(media_root, "alice")
    own.mkdir(parents=True)
    own_files = [own / FileBrowseService.thumbnail_cache_name(h, s) for s in SIZES] + [own / f"{h}_small.jpg"]
    for f in own_files:
        f.write_bytes(b"x")
    # "[nas]" as a pattern would match "Photos n"; that tree must stay intact.
    decoy = FileBrowseService.thumbnail_cache_dir(str(tmp_path / "Photos n"), "alice")
    decoy.mkdir(parents=True)
    decoy_file = decoy / FileBrowseService.thumbnail_cache_name(h, "small")
    decoy_file.write_bytes(b"x")

    assert FileBrowseService.remove_thumbnails_for_hash(media_root, "alice", h) == len(own_files)
    assert not any(f.exists() for f in own_files)
    assert decoy_file.exists()


async def test_cleanup_skips_directories_of_unknown_users(lib):
    ghost = FileBrowseService.thumbnail_cache_dir(lib.media_root, "ghost")
    ghost.mkdir(parents=True)
    files = [
        ghost / FileBrowseService.thumbnail_cache_name("c" * 64, "small"),
        ghost / f"{'c' * 64}_small.jpg",  # legacy name would otherwise be "obsolete"
    ]
    for f in files:
        f.write_bytes(b"x")
    assert await _run_thumbnail_cleanup(lib.db_path, lib.media_root) == ZERO
    assert all(f.exists() for f in files)


@pytest.mark.parametrize("legacy_name", [".thumbnails", ".THUMBNAILS"])
async def test_cleanup_disabled_when_a_user_is_named_like_the_cache_dir(
    lib, legacy_name, caplog, monkeypatch
):
    # setup_logging (run by other tests) stops propagation to caplog's handler.
    monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
    # A legacy user (created before F5's username rule) named like the cache
    # dir stores originals inside it; a device dir "alice" there looks exactly
    # like alice's cache, and "beach_small.jpg" parses as a legacy thumbnail.
    await lib.db.execute(
        "INSERT INTO users (id, username, password_hash) VALUES (3, ?, 'x')", (legacy_name,)
    )
    await lib.db.commit()
    alice_dir = FileBrowseService.thumbnail_cache_dir(lib.media_root, "alice")
    alice_dir.mkdir(parents=True)
    original = alice_dir / "beach_small.jpg"
    unknown_hash_cache = alice_dir / FileBrowseService.thumbnail_cache_name("e" * 64, "small")
    for f in (original, unknown_hash_cache):
        f.write_bytes(b"x")
    caplog.set_level(logging.ERROR, logger="photovault.background_tasks")
    assert await _run_thumbnail_cleanup(lib.db_path, lib.media_root) == ZERO
    assert original.exists()
    assert unknown_hash_cache.exists()
    assert "collides with the thumbnail cache" in caplog.text
