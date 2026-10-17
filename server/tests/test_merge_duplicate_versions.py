"""Tests for scripts/merge_duplicate_versions.py (FEAT-003, design section 12).

Every test builds its own library: the database in ``tmp_path/db/`` and the
media_root in ``tmp_path/media/``. After construction the WAL is checkpointed
(TRUNCATE) and the connection closed before any snapshot is taken. No test
talks to a network host.
"""

from __future__ import annotations

import fcntl
import hashlib
import os
import socket
import sqlite3
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import aiosqlite
import pytest

import app.core.config as config_module
import app.core.database as database_module
from app.core.database import init_db
from app.services import version_service as vs
from app.services.face_analyzer import _encode_vector
from app.services.file_browse_service import FileBrowseService
from app.services.version_service import VERSION_RETENTION_DAYS, VersionService
from scripts import merge_duplicate_versions as mdv

SERVER_DIR = Path(__file__).resolve().parent.parent
SCRIPT_PATH = SERVER_DIR / "scripts" / "merge_duplicate_versions.py"

DEVICE = "OPPO Find X9 Ultra"
DEVICE_DIR = "alice/OPPO_Find_X9_Ultra/DCIM/Camera"
OTHER_DEVICE = "Pixel9Pro"
OTHER_DIR = "alice/Pixel9Pro/DCIM/Camera"
OLD_TIME = "2026-09-17 15:43:37"
NEW_TIME = "2026-10-15 09:00:00"
PAIR_KEYS = ("A", "B", "C")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def old_bytes(key: str) -> bytes:
    return b"\xff\xd8" + f"old-jpeg-{key}".encode() * 200


def new_bytes(key: str) -> bytes:
    return old_bytes(key) + b"travel.info-trailer" * 20


def origin(n: int) -> str:
    return f"content://media/external/images/media/{1000 + n}"


# ---------------------------------------------------------------------------
# Fixtures and helpers
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _no_server_settings(monkeypatch):
    """The script must never read the server configuration."""

    def forbidden():
        raise AssertionError("the repair script must not read the server settings")

    monkeypatch.setattr(config_module, "get_settings", forbidden)
    monkeypatch.setattr(database_module, "get_settings", forbidden)
    monkeypatch.setattr(vs, "get_settings", forbidden)


class Library:
    """Builds the test database and media_root with sqlite3 (synchronous)."""

    def __init__(self, tmp_path: Path) -> None:
        self.db_dir = tmp_path / "db"
        self.media = tmp_path / "media"
        self.db_dir.mkdir()
        self.media.mkdir()
        self.db_path = str(self.db_dir / "photovault.db")
        self.media_root = str(self.media)
        self.ids: dict[str, int] = {}
        self.conn: sqlite3.Connection | None = None

    def add(
        self,
        tag: str,
        disk_name: str,
        file_name: str,
        content: bytes,
        *,
        device: str = DEVICE,
        rel_dir: str = DEVICE_DIR,
        origin_path: str = origin(0),
        created_at: str = OLD_TIME,
        user_id: int = 1,
        write: bool = True,
        **cols,
    ) -> int:
        directory = self.media / rel_dir
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / disk_name
        if write:
            path.write_bytes(content)
        values = {
            "user_id": user_id, "file_hash": sha(content), "file_path": str(path),
            "original_path": origin_path, "device_name": device, "file_size": len(content),
            "file_name": file_name, "mime_type": "image/jpeg", "media_type": "image",
            "created_at": created_at, **cols,
        }
        names = ", ".join(values)
        marks = ", ".join("?" for _ in values)
        cursor = self.conn.execute(
            f"INSERT INTO file_records ({names}) VALUES ({marks})", tuple(values.values())
        )
        self.ids[tag] = cursor.lastrowid
        return cursor.lastrowid

    def path(self, tag: str) -> str:
        return self.row(tag)["file_path"]

    def row(self, tag: str):
        return query(self, "SELECT * FROM file_records WHERE id = ?", (self.ids[tag],))[0]


def query(lib: Library, sql: str, params: tuple = ()):
    conn = sqlite3.connect(lib.db_path)
    conn.row_factory = sqlite3.Row
    try:
        return conn.execute(sql, params).fetchall()
    finally:
        conn.close()


def as_dict(row) -> dict:
    return {key: row[key] for key in row.keys()}


async def build_library(tmp_path: Path, mutate=None) -> Library:
    lib = Library(tmp_path)
    await init_db(lib.db_path)
    conn = sqlite3.connect(lib.db_path)
    conn.execute("PRAGMA foreign_keys=ON")
    lib.conn = conn
    conn.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'x')")

    # Three pairs "name + name_1", same device, same directory, same URI.
    for i, key in enumerate(PAIR_KEYS, start=1):
        name = f"IMG_{key}.jpg"
        lib.add(f"O{key}", name, name, old_bytes(key), origin_path=origin(i),
                iso=50, camera_model="OLD-MODEL", meta_status="ok")
        lib.add(f"N{key}", f"IMG_{key}_1.jpg", name, new_bytes(key), origin_path=origin(i),
                created_at=NEW_TIME, iso=100, camera_model="PHN110", meta_status="ok",
                exif_time="2026:09:17 15:43:37", focal_length=6.7, is_ultra_hdr=1)

    # Interference.
    lib.add("X1", "IMG_X.jpg", "IMG_X.jpg", b"x-oppo", origin_path=origin(10))
    lib.add("X2", "IMG_X.jpg", "IMG_X.jpg", b"x-pixel", device=OTHER_DEVICE,
            rel_dir=OTHER_DIR, origin_path=origin(10))
    lib.add("T0", "IMG_T.jpg", "IMG_T.jpg", b"t0", origin_path=origin(11))
    lib.add("T1", "IMG_T_1.jpg", "IMG_T.jpg", b"t1", origin_path=origin(11), created_at=NEW_TIME)
    lib.add("T2", "IMG_T_2.jpg", "IMG_T.jpg", b"t2", origin_path=origin(11), created_at=NEW_TIME)
    lib.add("M0", "IMG_M.jpg", "IMG_M.jpg", b"m0", origin_path=origin(12))
    lib.add("M1", "IMG_M-copy.jpg", "IMG_M.jpg", b"m1", origin_path=origin(12),
            created_at=NEW_TIME)
    lib.add("U0", "IMG_U.jpg", "IMG_U.jpg", b"u0", origin_path=origin(13))
    lib.add("U1", "IMG_U_1.jpg", "IMG_U.jpg", b"u1", origin_path=origin(14),
            created_at=NEW_TIME)
    lib.add("P", "IMG_P.jpg", "IMG_P.jpg", b"plain", origin_path=origin(15))
    lib.add("D", "IMG_D.jpg", "IMG_D.jpg", b"trashed", origin_path=origin(16),
            deleted_at="2026-10-01 00:00:00")
    lib.add("E", "IMG_E.jpg", "IMG_E.jpg", b"purged", origin_path=origin(17), write=False,
            deleted_at="2026-10-01 00:00:00", purged_at="2026-10-02 00:00:00")
    # A reference record (another path) pointing to N of pair A.
    lib.add("R", "IMG_R.jpg", "IMG_R.jpg", new_bytes("A"), rel_dir="alice/refs",
            origin_path=origin(18), write=False, is_reference=1, reference_to=lib.ids["NA"])

    o_a, n_a, o_b, p = lib.ids["OA"], lib.ids["NA"], lib.ids["OB"], lib.ids["P"]
    # Pair A: analysis rows on both O and N.
    conn.execute(
        "INSERT INTO photo_gps (file_id, user_id, latitude, longitude, city) VALUES (?, 1, 1.0, 1.0, 'Old')",
        (o_a,),
    )
    conn.execute(
        "INSERT INTO photo_gps (file_id, user_id, latitude, longitude, city) VALUES (?, 1, 2.0, 2.0, 'New')",
        (n_a,),
    )
    conn.executemany(
        "INSERT INTO photo_scenes (file_id, user_id, scene_label, confidence) VALUES (?, 1, ?, 0.9)",
        [(o_a, "old-sky"), (o_a, "old-sea"), (n_a, "new-beach")],
    )
    conn.execute("INSERT INTO face_clusters (id, user_id, face_count) VALUES (1, 1, 2)")
    conn.execute("INSERT INTO face_clusters (id, user_id, face_count) VALUES (2, 1, 1)")
    embedding = _encode_vector([0.1, 0.2, 0.3])
    conn.executemany(
        """INSERT INTO faces (id, file_id, user_id, cluster_id, bbox, det_score, embedding)
           VALUES (?, ?, 1, ?, '[0,0,1,1]', 0.99, ?)""",
        [(1, o_a, 1, embedding), (2, p, 1, embedding), (3, n_a, 2, embedding)],
    )
    conn.execute("UPDATE face_clusters SET cover_face_id = 1 WHERE id = 1")
    conn.execute("UPDATE face_clusters SET cover_face_id = 3 WHERE id = 2")
    # Pair B: only O has a GPS row (kept).
    conn.execute(
        "INSERT INTO photo_gps (file_id, user_id, latitude, longitude, city) VALUES (?, 1, 3.0, 3.0, 'KeepB')",
        (o_b,),
    )
    # Thumbnail cache of pair A (old and new hash).
    thumbs = lib.media / ".thumbnails" / "alice"
    thumbs.mkdir(parents=True)
    (thumbs / f"{sha(old_bytes('A'))}_256_v2.jpg").write_bytes(b"old-thumb")
    (thumbs / f"{sha(new_bytes('A'))}_256_v2.jpg").write_bytes(b"new-thumb")

    if mutate is not None:
        mutate(lib, conn)
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.close()
    lib.conn = None
    return lib


def snapshot(lib: Library) -> dict:
    """sha256 manifest: main database file + media_root (no -shm / -wal)."""
    manifest: dict[str, str] = {"<db>": sha(Path(lib.db_path).read_bytes())}
    manifest["<db-dir>"] = ",".join(
        sorted(n for n in os.listdir(lib.db_dir) if not n.endswith(("-shm", "-wal")))
    )
    for path in sorted(lib.media.rglob("*")):
        if path.name.endswith((".db-shm", ".db-wal")):
            continue
        rel = str(path.relative_to(lib.media))
        manifest[rel] = "<dir>" if path.is_dir() else sha(path.read_bytes())
    return manifest


def assert_wal_empty(lib: Library) -> None:
    wal = Path(lib.db_path + "-wal")
    assert not wal.exists() or wal.stat().st_size == 0


async def run_script(lib: Library, capsys, *extra: str) -> tuple[int, str]:
    args = mdv.parse_args(["--db", lib.db_path, "--media-root", lib.media_root, *extra])
    rc = await mdv.run(args)
    captured = capsys.readouterr()
    return rc, captured.out + captured.err


async def device_stats(lib: Library) -> dict[str, tuple[int, int, int]]:
    conn = await aiosqlite.connect(lib.db_path)
    conn.row_factory = aiosqlite.Row
    try:
        stats = await FileBrowseService(conn, lib.media_root).get_device_stats(1)
    finally:
        await conn.close()
    return {s.name: (s.backed_up_count, s.trashed_count, s.purged_count) for s in stats}


def assert_merged(lib: Library, key: str, old_ids: dict[str, int], before: dict) -> None:
    o_id, n_id = old_ids[f"O{key}"], old_ids[f"N{key}"]
    o_before, n_before = before[f"O{key}"], before[f"N{key}"]
    row = query(lib, "SELECT * FROM file_records WHERE id = ?", (o_id,))[0]
    # Old id kept, content columns from N.
    for column in ("file_hash", "file_size", "iso", "camera_model", "exif_time",
                   "focal_length", "is_ultra_hdr", "original_path"):
        assert row[column] == n_before[column], column
    for column in ("file_path", "device_name", "file_name", "created_at", "user_id"):
        assert row[column] == o_before[column], column
    assert Path(row["file_path"]).read_bytes() == new_bytes(key)
    assert not Path(n_before["file_path"]).exists()
    assert query(lib, "SELECT id FROM file_records WHERE id = ?", (n_id,)) == []
    # One history version with the old content, kept for 30 days.
    versions = query(lib, "SELECT * FROM file_versions WHERE file_record_id = ?", (o_id,))
    assert len(versions) == 1
    version = versions[0]
    assert version["file_hash"] == o_before["file_hash"]
    assert version["file_size"] == o_before["file_size"]
    stored = Path(version["stored_path"])
    versions_root = Path(os.path.realpath(lib.media_root)) / ".versions" / str(o_id)
    assert stored.parent == versions_root
    assert stored.read_bytes() == old_bytes(key)
    span = datetime.fromisoformat(version["expires_at"]) - datetime.fromisoformat(
        version["replaced_at"]
    )
    assert span.days == VERSION_RETENTION_DAYS
    assert list(Path(row["file_path"]).parent.glob(".pv-*")) == []


def assert_untouched(lib: Library, tags, before: dict, contents: dict[str, bytes]) -> None:
    for tag in tags:
        rows = query(lib, "SELECT * FROM file_records WHERE id = ?", (before[tag]["id"],))
        assert len(rows) == 1, tag
        assert as_dict(rows[0]) == before[tag], tag
        if tag in contents:
            assert Path(before[tag]["file_path"]).read_bytes() == contents[tag], tag


def all_rows(lib: Library) -> dict[str, dict]:
    return {tag: as_dict(lib.row(tag)) for tag in lib.ids}


INTERFERENCE = ("X1", "X2", "T0", "T1", "T2", "M0", "M1", "U0", "U1", "P", "D", "E")


# ---------------------------------------------------------------------------
# Acceptance 23-30
# ---------------------------------------------------------------------------


class TestDryRun:
    async def test_dry_run_changes_no_byte(self, tmp_path, capsys):
        """Acceptance 23."""
        lib = await build_library(tmp_path)
        before = snapshot(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert snapshot(lib) == before
        assert_wal_empty(lib)
        assert "模式：dry-run" in out
        assert out.count("动作：将合并") == 3
        assert "待合并：3 对" in out
        assert "跳过：同名记录 3 条（非成对）" in out
        assert "跳过：不同设备同名（不处理）" in out
        assert "物理文件不是同目录的「原名 + 原名_N」模式" in out
        assert "URI：不一致" in out
        assert "预计：用户 alice（id=1） 设备 OPPO Find X9 Ultra 的已备份计数减少 3" in out
        assert "实际" not in out

    async def test_second_dry_run_after_apply_finds_nothing(self, tmp_path, capsys):
        """Acceptance 29."""
        lib = await build_library(tmp_path)
        rc, _ = await run_script(lib, capsys, "--apply")
        assert rc == 0
        before = snapshot(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert "待合并：0 对" in out
        assert "将合并" not in out
        assert snapshot(lib) == before


class TestApply:
    async def test_each_pair_is_merged(self, tmp_path, capsys):
        """Acceptance 24."""
        lib = await build_library(tmp_path)
        before = all_rows(lib)
        ids = dict(lib.ids)
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        assert "在场锁：已获取" in out
        assert out.count("动作：已合并") == 3
        assert "成功：3 对" in out and "失败：0 对" in out
        for key in PAIR_KEYS:
            assert_merged(lib, key, ids, before)
        assert query(lib, "SELECT COUNT(*) AS c FROM file_version_journal")[0]["c"] == 0
        # Thumbnail: the new content's cache stays, the old hash's is gone.
        thumbs = lib.media / ".thumbnails" / "alice"
        assert (thumbs / f"{sha(new_bytes('A'))}_256_v2.jpg").exists()
        assert not (thumbs / f"{sha(old_bytes('A'))}_256_v2.jpg").exists()

    async def test_children_and_references_move_to_old_id(self, tmp_path, capsys):
        """Acceptance 25."""
        lib = await build_library(tmp_path)
        o_a, n_a, o_b, n_b = (lib.ids[k] for k in ("OA", "NA", "OB", "NB"))
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        gps = query(lib, "SELECT * FROM photo_gps WHERE file_id = ?", (o_a,))
        assert [(g["city"], g["latitude"]) for g in gps] == [("New", 2.0)]
        scenes = query(lib, "SELECT scene_label FROM photo_scenes WHERE file_id = ?", (o_a,))
        assert [s["scene_label"] for s in scenes] == ["new-beach"]
        faces = query(lib, "SELECT id, cluster_id FROM faces WHERE file_id = ?", (o_a,))
        assert [(f["id"], f["cluster_id"]) for f in faces] == [(3, 2)]
        clusters = {
            c["id"]: (c["face_count"], c["cover_face_id"])
            for c in query(lib, "SELECT * FROM face_clusters")
        }
        assert clusters == {1: (1, 2), 2: (1, 3)}  # cluster 1 recomputed without O's face
        assert lib.row("R")["reference_to"] == o_a
        # Pair B: N had no GPS row, O's is kept.
        assert [g["city"] for g in query(lib, "SELECT city FROM photo_gps WHERE file_id = ?", (o_b,))] == ["KeepB"]
        for n_id in (n_a, n_b):
            for table, column in (("photo_gps", "file_id"), ("photo_scenes", "file_id"),
                                  ("faces", "file_id"), ("file_records", "reference_to"),
                                  ("file_versions", "file_record_id")):
                rows = query(lib, f"SELECT 1 FROM {table} WHERE {column} = ?", (n_id,))
                assert rows == [], (table, n_id)
        assert query(lib, "SELECT COUNT(*) AS c FROM photo_gps WHERE file_id = ?", (o_a,))[0]["c"] == 1
        conn = sqlite3.connect(lib.db_path)
        try:
            assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
        finally:
            conn.close()

    async def test_interference_is_untouched_and_reported(self, tmp_path, capsys):
        """Acceptance 26."""
        lib = await build_library(tmp_path)
        before = all_rows(lib)
        contents = {"X1": b"x-oppo", "X2": b"x-pixel", "T0": b"t0", "T1": b"t1", "T2": b"t2",
                    "M0": b"m0", "M1": b"m1", "U0": b"u0", "U1": b"u1", "P": b"plain",
                    "D": b"trashed"}
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        lib.ids = {tag: before[tag]["id"] for tag in INTERFERENCE}
        assert_untouched(lib, INTERFERENCE, before, contents)
        assert "文件名 IMG_T.jpg：跳过：同名记录 3 条（非成对）" in out
        assert "文件名 IMG_X.jpg：跳过：不同设备同名（不处理），设备：OPPO Find X9 Ultra、Pixel9Pro" in out
        assert "跳过：物理文件不是同目录的「原名 + 原名_N」模式" in out
        assert "跳过：URI 不一致" in out
        assert "跳过：4 项" in out
        for tag in ("U0", "U1", "M0", "M1"):
            assert query(lib, "SELECT 1 FROM file_versions WHERE file_record_id = ?",
                         (before[tag]["id"],)) == []

    async def test_backed_up_count_drops_by_merged_pairs(self, tmp_path, capsys):
        """Acceptance 27."""
        lib = await build_library(tmp_path)
        before = await device_stats(lib)
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        after = await device_stats(lib)
        assert after[DEVICE][0] == before[DEVICE][0] - 3
        assert after[DEVICE][1:] == before[DEVICE][1:]
        assert after[OTHER_DEVICE] == before[OTHER_DEVICE]
        assert "预计：用户 alice（id=1） 设备 OPPO Find X9 Ultra 的已备份计数减少 3" in out
        assert (
            f"实际：用户 alice（id=1） 设备 OPPO Find X9 Ultra 的已备份计数减少 3"
            f"（{before[DEVICE][0]} → {after[DEVICE][0]}；回收站 1 → 1，已清除 1 → 1）"
        ) in out

    async def test_fault_in_one_pair_rolls_back_only_that_pair(self, tmp_path, capsys, monkeypatch):
        """Acceptance 28: the second pair fails after the switch; it is restored."""
        lib = await build_library(tmp_path)
        before = all_rows(lib)
        ids = dict(lib.ids)

        class Faulty(VersionService):
            calls = 0

            async def replace_record_content(self, **kwargs):
                Faulty.calls += 1
                if Faulty.calls == 2:
                    def hook(stage: str) -> None:
                        if stage == "after_swap":
                            raise RuntimeError("注入故障")
                    self._fault_hook = hook
                else:
                    self._fault_hook = lambda stage: None
                return await super().replace_record_content(**kwargs)

        monkeypatch.setattr(mdv, "VersionService", Faulty)
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 1
        assert Faulty.calls == 3
        assert "成功：2 对" in out and "失败：1 对" in out
        assert "失败：db：" in out and "注入故障" in out and "这一对已回滚" in out
        assert_merged(lib, "A", ids, before)
        assert_merged(lib, "C", ids, before)
        lib.ids = {"OB": ids["OB"], "NB": ids["NB"]}
        assert_untouched(lib, ("OB", "NB"), before,
                         {"OB": old_bytes("B"), "NB": new_bytes("B")})
        assert query(lib, "SELECT 1 FROM file_versions WHERE file_record_id = ?", (ids["OB"],)) == []
        assert query(lib, "SELECT COUNT(*) AS c FROM file_version_journal")[0]["c"] == 0
        assert not (Path(os.path.realpath(lib.media_root)) / ".versions" / str(ids["OB"])).exists()
        assert list(Path(before["OB"]["file_path"]).parent.glob(".pv-*")) == []
        assert [g["city"] for g in query(lib, "SELECT city FROM photo_gps WHERE file_id = ?",
                                         (ids["OB"],))] == ["KeepB"]

        # The pair stays mergeable: a clean re-run merges it.
        monkeypatch.setattr(mdv, "VersionService", VersionService)
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        assert "成功：1 对" in out
        assert_merged(lib, "B", ids, before)


class TestArguments:
    def test_missing_arguments_exit_2_in_chinese(self):
        """Acceptance 30 (no argument at all: no database is touched)."""
        env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
        result = subprocess.run(
            [sys.executable, "-m", "scripts.merge_duplicate_versions"],
            cwd=SERVER_DIR, capture_output=True, text=True, env=env, timeout=60,
        )
        assert result.returncode == 2
        assert "必须显式指定 --db 和 --media-root（不会读取服务端配置）" in result.stderr

    @pytest.mark.parametrize("which", ["db", "media_root"])
    async def test_one_argument_missing(self, tmp_path, capsys, which):
        argv = ["--media-root", str(tmp_path)] if which == "db" else ["--db", str(tmp_path / "x.db")]
        rc = await mdv.run(mdv.parse_args(argv))
        assert rc == 2
        assert mdv.MISSING_ARGS_MESSAGE in capsys.readouterr().err

    async def test_invalid_paths(self, tmp_path, capsys):
        media = tmp_path / "media"
        media.mkdir()
        rc = await mdv.run(mdv.parse_args(["--db", str(tmp_path / "nope.db"),
                                           "--media-root", str(media)]))
        assert rc == 2
        assert "数据库文件不存在" in capsys.readouterr().err
        assert not (tmp_path / "nope.db").exists()
        db_file = tmp_path / "x.db"
        db_file.write_bytes(b"")
        rc = await mdv.run(mdv.parse_args(["--db", str(db_file),
                                           "--media-root", str(tmp_path / "nope")]))
        assert rc == 2
        assert "media_root 不存在或不是目录" in capsys.readouterr().err
        rc = await mdv.run(mdv.parse_args(["--db", str(db_file), "--media-root", str(media),
                                           "--user", "abc"]))
        assert rc == 2
        assert "--user 必须是整数" in capsys.readouterr().err

    def test_no_settings_and_no_default_paths(self):
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        assert "get_settings" not in source
        args = mdv.parse_args([])
        assert args.db is None and args.media_root is None and args.apply is False
        for host in ("192.168.", "localhost", "127.0.0.1"):
            assert host not in source


# ---------------------------------------------------------------------------
# Additional cases (design 18.1)
# ---------------------------------------------------------------------------


def _drop_version_tables(lib: Library, conn: sqlite3.Connection) -> None:
    conn.execute("DROP TABLE file_versions")
    conn.execute("DROP TABLE file_version_journal")


def _dead_pid() -> int:
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    return proc.pid


class TestSafetyChecks:
    async def test_without_version_tables(self, tmp_path, capsys):
        lib = await build_library(tmp_path, mutate=_drop_version_tables)
        before = snapshot(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert "目标库尚未建版本表（新版本服务端尚未在此库上启动过）" in out
        assert "待合并：3 对" in out
        assert snapshot(lib) == before
        assert_wal_empty(lib)

        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 2
        assert "目标库缺少版本表" in out
        after = snapshot(lib)
        assert after["<db>"] == before["<db>"]
        assert not (lib.media / ".versions").exists()

    async def test_leftover_journal_is_listed_then_recovered(self, tmp_path, capsys):
        pid = _dead_pid()

        def add_journal(lib: Library, conn: sqlite3.Connection) -> None:
            o_id = lib.ids["OA"]
            row = conn.execute("SELECT file_path, file_hash FROM file_records WHERE id = ?",
                               (o_id,)).fetchone()
            final_path, old_hash = row
            parent = os.path.dirname(final_path)
            conn.execute(
                """INSERT INTO file_version_journal
                   (op, state, owner, user_id, file_record_id, old_hash, new_hash, final_path,
                    version_path, rollback_path, staging_path, cleanup_path, replaced_at,
                    created_at)
                   VALUES ('upload', 'prepared', ?, 1, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)""",
                (
                    f"{socket.gethostname()}:{pid}:deadbeefcafe", o_id, old_hash,
                    sha(b"something-else"), final_path,
                    os.path.join(lib.media_root, ".versions", str(o_id),
                                 f"20260101T000000Z_{old_hash[:8]}.jpg"),
                    os.path.join(parent, ".pv-rollback-0123456789ab.jpg"),
                    os.path.join(parent, ".pv-staging-0123456789ab.jpg"),
                    "2026-01-01T00:00:00+00:00", "2026-01-01T00:00:00+00:00",
                ),
            )

        lib = await build_library(tmp_path, mutate=add_journal)
        ids = dict(lib.ids)
        before_rows = all_rows(lib)
        before = snapshot(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert "遗留版本操作日志（待恢复，需 --apply 或服务端处理" in out
        assert f"journal id=1 记录 id={ids['OA']}" in out
        assert "跳过：存在未完成的版本操作日志（journal id=[1]）" in out
        assert "待合并：2 对" in out
        assert snapshot(lib) == before
        assert len(query(lib, "SELECT * FROM file_version_journal")) == 1

        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        assert "启动恢复：已处理遗留版本操作日志 1 条" in out
        assert "成功：3 对" in out
        assert query(lib, "SELECT * FROM file_version_journal") == []
        for key in PAIR_KEYS:
            assert_merged(lib, key, ids, before_rows)

    async def test_missing_user_is_skipped(self, tmp_path, capsys):
        def ghost_pair(lib: Library, conn: sqlite3.Connection) -> None:
            conn.commit()  # PRAGMA foreign_keys is a no-op inside a transaction
            conn.execute("PRAGMA foreign_keys=OFF")
            lib.add("G0", "IMG_G.jpg", "IMG_G.jpg", b"g0", user_id=99,
                    rel_dir="ghost/OPPO_Find_X9_Ultra/DCIM/Camera", origin_path=origin(30))
            lib.add("G1", "IMG_G_1.jpg", "IMG_G.jpg", b"g1", user_id=99,
                    rel_dir="ghost/OPPO_Find_X9_Ultra/DCIM/Camera", origin_path=origin(30),
                    created_at=NEW_TIME)
            conn.commit()
            conn.execute("PRAGMA foreign_keys=ON")

        lib = await build_library(tmp_path, mutate=ghost_pair)
        before = all_rows(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert "用户 id=99（用户不存在）" in out
        assert "跳过：用户不存在" in out
        assert "待合并：3 对" in out
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        lib.ids = {"G0": before["G0"]["id"], "G1": before["G1"]["id"]}
        assert_untouched(lib, ("G0", "G1"), before, {"G0": b"g0", "G1": b"g1"})

    async def test_referenced_original_is_skipped(self, tmp_path, capsys):
        def reference_o(lib: Library, conn: sqlite3.Connection) -> None:
            lib.add("RC", "IMG_RC.jpg", "IMG_RC.jpg", old_bytes("C"), rel_dir="alice/refs",
                    origin_path=origin(31), write=False, is_reference=1,
                    reference_to=lib.ids["OC"])

        lib = await build_library(tmp_path, mutate=reference_o)
        before = all_rows(lib)
        rc, out = await run_script(lib, capsys)
        assert rc == 0
        assert "跳过：存在引用该记录的条目" in out
        assert "待合并：2 对" in out
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out
        assert "成功：2 对" in out
        lib.ids = {k: before[k]["id"] for k in ("OC", "NC", "RC")}
        assert_untouched(lib, ("OC", "NC", "RC"), before,
                         {"OC": old_bytes("C"), "NC": new_bytes("C")})

    async def test_purged_record_with_new_hash_at_target_is_skipped(self, tmp_path, capsys):
        def purged_at_target(lib: Library, conn: sqlite3.Connection) -> None:
            lib.add("Z", "IMG_B.jpg", "IMG_B.jpg", new_bytes("B"), origin_path=origin(2),
                    write=False, deleted_at="2026-10-01 00:00:00",
                    purged_at="2026-10-02 00:00:00")

        lib = await build_library(tmp_path, mutate=purged_at_target)
        before = all_rows(lib)
        rc, out = await run_script(lib, capsys, "--apply")
        assert rc == 0, out  # a skip, not a failure
        assert "跳过：目标路径已有同哈希的历史记录" in out
        assert "成功：2 对" in out and "失败：0 对" in out
        lib.ids = {k: before[k]["id"] for k in ("OB", "NB", "Z")}
        assert_untouched(lib, ("OB", "NB", "Z"), before,
                         {"OB": old_bytes("B"), "NB": new_bytes("B")})

    async def test_apply_refused_while_server_holds_presence_lock(self, tmp_path, capsys):
        lib = await build_library(tmp_path)
        versions = lib.media / ".versions"
        versions.mkdir()
        fd = os.open(versions / ".server-presence.lock", os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            before = snapshot(lib)
            rc, out = await run_script(lib, capsys, "--apply")
            assert rc == 2
            assert "检测到服务端或另一个修复脚本正在使用该 media_root，请先停止服务端后再执行 --apply" in out
            assert snapshot(lib) == before
        finally:
            os.close(fd)

    async def test_allow_uri_mismatch_merges_that_pair_too(self, tmp_path, capsys):
        lib = await build_library(tmp_path)
        u0, u1 = lib.ids["U0"], lib.ids["U1"]
        rc, out = await run_script(lib, capsys, "--allow-uri-mismatch")
        assert rc == 0
        assert "URI：URI 不一致（已按参数放行）" in out
        assert "待合并：4 对" in out
        rc, out = await run_script(lib, capsys, "--apply", "--allow-uri-mismatch")
        assert rc == 0, out
        assert "成功：4 对" in out
        row = query(lib, "SELECT * FROM file_records WHERE id = ?", (u0,))[0]
        assert row["file_hash"] == sha(b"u1") and row["original_path"] == origin(14)
        assert query(lib, "SELECT 1 FROM file_records WHERE id = ?", (u1,)) == []

    async def test_filters_limit_the_scope(self, tmp_path, capsys):
        lib = await build_library(tmp_path)
        rc, out = await run_script(lib, capsys, "--device", OTHER_DEVICE)
        assert rc == 0
        assert "待合并：0 对" in out
        rc, out = await run_script(lib, capsys, "--user", "1", "--device", DEVICE)
        assert rc == 0
        assert "待合并：3 对" in out
