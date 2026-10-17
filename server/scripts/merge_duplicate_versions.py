#!/usr/bin/env python3
"""合并文件版本化上线前留下的「原名 + 原名_N」重复记录。

文件版本化上线之前，同一台设备上同一个文件的内容被改动（例如手机往原图尾部
追加 travel.info）后重新上传，服务端会另存为 ``原名_1.ext`` 并新增一条
file_records。这个一次性脚本把这样的成对记录合并成「旧 id + 新内容 + 一条历史
版本」：旧记录 O 的 id、路径、设备、文件名、创建时间保持不变，内容换成新记录 N
的内容；O 的旧内容进入 file_versions 和 ``{media_root}/.versions/<O.id>/``，
保留 30 天；N 的分析结果和引用迁到 O，N 被删除，``原名_N`` 文件被删除。
合并 N 对后，对应设备的已备份计数正好减少 N。

用法（在 server/ 目录下，用系统 python3）：

    # 只读预览（默认）：只打印计划，数据库和文件系统字节级不变
    python3 -m scripts.merge_duplicate_versions --db PATH --media-root PATH

    # 实际合并：必须先停止服务端
    python3 -m scripts.merge_duplicate_versions --db PATH --media-root PATH --apply

    # 可选参数
    python3 -m scripts.merge_duplicate_versions --db PATH --media-root PATH \\
        [--apply] [--user ID] [--device NAME] [--allow-uri-mismatch]

参数：
- ``--db``、``--media-root``：必须显式指定，没有默认值，脚本不读取服务端配置。
- ``--apply``：实际执行合并。不带时为 dry-run：数据库以只读 URI 打开，不创建
  任何文件或目录，不处理遗留的版本操作日志（只列出，并跳过相关的对）。
- ``--user ID`` / ``--device NAME``：只处理指定用户 / 设备。
- ``--allow-uri-mismatch``：手机端原始标识（original_path）不一致的对也合并，
  报告里仍会标出「URI 不一致（已按参数放行）」。设备必须相同，不受这个参数影响。

注意：
- ``--apply`` 会对 ``{media_root}/.versions/.server-presence.lock`` 取排他锁，
  服务端运行时（持有共享锁）拿不到，脚本以退出码 2 结束，不做任何修改。
- 每一对独立合并：某一对失败时它回滚到原状，报告里写明原因，其余的对照常处理。
- 退出码：0 全部成功（包括 dry-run）；1 至少一对合并失败；2 参数或环境错误。
- 建议先 dry-run，核对报告后再 ``--apply``；是否、何时在生产库上执行由管理员决定。
"""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import hashlib
import os
import re
import sqlite3
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Optional
from urllib.parse import quote

# Ensure the project root (server/) is importable so `app` resolves when the
# script is run directly rather than via `python -m`.
_SERVER_ROOT = Path(__file__).resolve().parent.parent
if str(_SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVER_ROOT))

import aiosqlite  # noqa: E402

from app.core.database import _MEDIA_METADATA_COLUMNS, _create_connection  # noqa: E402
from app.services.face_analyzer import FaceAnalyzer  # noqa: E402
from app.services.file_browse_service import effective_file_status  # noqa: E402
from app.services.path_locks import path_lock  # noqa: E402
from app.services.version_service import (  # noqa: E402
    SERVER_PRESENCE_LOCK_NAME,
    VERSIONS_DIRNAME,
    NeedsReclassify,
    VersionReplaceError,
    VersionService,
    _same_origin,
)

EXIT_OK = 0
EXIT_PAIR_FAILED = 1
EXIT_ENV_ERROR = 2

MISSING_ARGS_MESSAGE = "必须显式指定 --db 和 --media-root（不会读取服务端配置）"

# Columns copied from N to O (design 12.3). file_hash / file_size are passed
# as new_hash / new_size; replace_record_content overwrites them anyway.
_COPIED_COLUMNS: tuple[str, ...] = (
    "mime_type",
    "exif_time",
    "focal_length",
    "media_type",
    "is_motion_photo",
    "motion_video_offset",
    "is_ultra_hdr",
    "live_photo_group_id",
    "live_photo_type",
    *(name for name, _type in _MEDIA_METADATA_COLUMNS),
    "original_path",
)

_CANDIDATE_SQL = """
SELECT * FROM file_records
WHERE deleted_at IS NULL AND purged_at IS NULL
  AND (is_reference = 0 OR is_reference IS NULL)
"""

_VERSION_TABLES = ("file_versions", "file_version_journal")

# Message of VersionReplaceError when the COMMIT outcome could not be determined.
_COMMIT_UNKNOWN_MESSAGE = "commit outcome unknown"


# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------


class _ArgumentParser(argparse.ArgumentParser):
    """argparse with a Chinese error line and exit code 2."""

    def error(self, message: str) -> None:  # type: ignore[override]
        self.print_usage(sys.stderr)
        print(f"参数错误：{message}", file=sys.stderr)
        sys.exit(EXIT_ENV_ERROR)


def build_parser() -> argparse.ArgumentParser:
    parser = _ArgumentParser(
        prog="python3 -m scripts.merge_duplicate_versions",
        description="合并「原名 + 原名_N」重复记录为「旧 id + 新内容 + 一条历史版本」（默认 dry-run）",
    )
    # --db / --media-root are deliberately not ``required``: the missing-argument
    # message is printed in Chinese by ``run`` (design 12.1).
    parser.add_argument("--db", default=None, help="数据库文件路径（必填，无默认值）")
    parser.add_argument("--media-root", default=None, help="照片存储目录 media_root（必填，无默认值）")
    parser.add_argument("--apply", action="store_true", help="实际执行合并（执行前必须先停止服务端）")
    parser.add_argument("--user", default=None, help="只处理指定 user_id")
    parser.add_argument("--device", default=None, help="只处理指定设备名（device_name 原样）")
    parser.add_argument(
        "--allow-uri-mismatch",
        action="store_true",
        help="original_path 不一致的对也合并（设备仍须相同）",
    )
    return parser


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    return build_parser().parse_args(argv)


def _err(message: str) -> None:
    print(message, file=sys.stderr)


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------


@dataclass
class _Pair:
    o: Any
    n: Any
    username: Optional[str]
    uri_note: str
    hash_note: str = "未校验"
    skip_reason: Optional[str] = None
    action: str = ""

    @property
    def mergeable(self) -> bool:
        return self.skip_reason is None


@dataclass
class _Plan:
    pairs: list[_Pair]
    skipped_groups: list[str]


def _sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _under(path: str, root_real: str) -> bool:
    real = os.path.realpath(path)
    return real.startswith(root_real.rstrip(os.sep) + os.sep)


def _user_label(user_id: int, usernames: dict[int, str]) -> str:
    name = usernames.get(user_id)
    return f"{name}（id={user_id}）" if name is not None else f"id={user_id}（用户不存在）"


# ---------------------------------------------------------------------------
# Database access
# ---------------------------------------------------------------------------


async def _connect_probe(database: str, *, uri: bool) -> aiosqlite.Connection:
    db = await aiosqlite.connect(database, uri=uri)
    try:
        cursor = await db.execute("SELECT COUNT(*) FROM sqlite_master")
        await cursor.fetchone()
    except BaseException:
        await db.close()
        raise
    db.row_factory = aiosqlite.Row
    return db


async def _open_readonly(db_path: str) -> tuple[Optional[aiosqlite.Connection], str]:
    """Open ``db_path`` read-only (design 12.1). Returns (conn, note or error)."""
    quoted = quote(db_path)
    try:
        return await _connect_probe(f"file:{quoted}?mode=ro", uri=True), ""
    except sqlite3.Error as exc:
        first_error = exc
    wal = db_path + "-wal"
    if os.path.exists(wal) and os.path.getsize(wal) > 0:
        return None, (
            "数据库只读打开失败，且存在未合并的 WAL；请先停止服务端，"
            f"或在可写目录的副本上运行 dry-run（{first_error}）"
        )
    try:
        conn = await _connect_probe(f"file:{quoted}?mode=ro&immutable=1", uri=True)
    except sqlite3.Error as exc:
        return None, f"数据库只读打开失败：{exc}"
    return conn, "（普通只读方式打不开，已改用 immutable=1 只读打开；-wal 为空）"


async def _open_writable(db_path: str) -> tuple[Optional[aiosqlite.Connection], str]:
    try:
        db = await _create_connection(db_path)
    except sqlite3.Error as exc:
        return None, f"数据库打开失败：{exc}"
    try:
        cursor = await db.execute("SELECT COUNT(*) FROM sqlite_master")
        await cursor.fetchone()
    except sqlite3.Error as exc:
        await db.close()
        return None, f"数据库打开失败：{exc}"
    return db, ""


async def _version_tables_present(db: aiosqlite.Connection) -> bool:
    cursor = await db.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
        _VERSION_TABLES,
    )
    names = {row[0] for row in await cursor.fetchall()}
    return names == set(_VERSION_TABLES)


async def _journal_rows(db: aiosqlite.Connection, has_tables: bool) -> list[Any]:
    if not has_tables:
        return []
    cursor = await db.execute("SELECT * FROM file_version_journal ORDER BY id")
    return list(await cursor.fetchall())


async def _usernames(db: aiosqlite.Connection) -> dict[int, str]:
    cursor = await db.execute("SELECT id, username FROM users")
    return {int(row["id"]): row["username"] for row in await cursor.fetchall()}


def _filters(args: argparse.Namespace) -> tuple[str, list[Any]]:
    where: list[str] = []
    params: list[Any] = []
    if args.user is not None:
        where.append("user_id = ?")
        params.append(args.user)
    if args.device is not None:
        where.append("device_name = ?")
        params.append(args.device)
    return "".join(f" AND {clause}" for clause in where), params


async def _status_counts(
    db: aiosqlite.Connection, args: argparse.Namespace
) -> dict[tuple[int, str], dict[str, int]]:
    """Per (user_id, device_name) counts, bucketed like ``get_device_stats``."""
    extra, params = _filters(args)
    cursor = await db.execute(
        "SELECT user_id, device_name, file_path, deleted_at, purged_at "
        f"FROM file_records WHERE 1 = 1{extra}",
        params,
    )
    counts: dict[tuple[int, str], dict[str, int]] = defaultdict(
        lambda: {"backed_up": 0, "trashed": 0, "purged": 0}
    )
    for row in await cursor.fetchall():
        if not row["device_name"]:
            continue
        status = effective_file_status(row["deleted_at"], row["purged_at"], row["file_path"])
        counts[(int(row["user_id"]), row["device_name"])][status] += 1
    return counts


async def _exists(db: aiosqlite.Connection, sql: str, params: tuple[Any, ...]) -> bool:
    cursor = await db.execute(f"SELECT EXISTS ({sql})", params)
    return bool((await cursor.fetchone())[0])


# ---------------------------------------------------------------------------
# Identification (design 12.2)
# ---------------------------------------------------------------------------


async def _collect(
    db: aiosqlite.Connection,
    args: argparse.Namespace,
    media_root: str,
    has_tables: bool,
    journal: list[Any],
    usernames: dict[int, str],
) -> _Plan:
    extra, params = _filters(args)
    cursor = await db.execute(f"{_CANDIDATE_SQL}{extra} ORDER BY id", params)
    records = list(await cursor.fetchall())

    groups: dict[tuple[int, str, str], list[Any]] = defaultdict(list)
    devices_by_name: dict[tuple[int, str], set[str]] = defaultdict(set)
    for record in records:
        key = (int(record["user_id"]), record["device_name"], record["file_name"])
        groups[key].append(record)
        devices_by_name[(int(record["user_id"]), record["file_name"])].add(record["device_name"])

    journal_ids: dict[int, list[int]] = defaultdict(list)
    journal_paths: dict[str, list[int]] = defaultdict(list)
    for row in journal:
        journal_ids[int(row["file_record_id"])].append(int(row["id"]))
        for key in ("final_path", "cleanup_path"):
            if row[key]:
                journal_paths[row[key]].append(int(row["id"]))

    root_real = os.path.realpath(media_root)
    pairs: list[_Pair] = []
    skipped: list[str] = []

    for (user_id, device, file_name), rows in sorted(groups.items(), key=lambda kv: kv[1][0]["id"]):
        if len(rows) < 2:
            continue
        if len(rows) > 2:
            skipped.append(
                f"用户 {_user_label(user_id, usernames)} 设备 {device} 文件名 {file_name}："
                f"跳过：同名记录 {len(rows)} 条（非成对），id={[r['id'] for r in rows]}"
            )
            continue
        o, n = sorted(rows, key=lambda r: r["id"])
        same = _same_origin(o["device_name"], o["original_path"], n["device_name"], n["original_path"])
        if same:
            uri_note = "一致"
        elif args.allow_uri_mismatch:
            uri_note = "URI 不一致（已按参数放行）"
        else:
            uri_note = "不一致"
        pair = _Pair(o=o, n=n, username=usernames.get(user_id), uri_note=uri_note)
        pair.skip_reason = await _check_pair(
            db, pair, root_real, same or args.allow_uri_mismatch, has_tables,
            journal_ids, journal_paths,
        )
        pairs.append(pair)

    for (user_id, file_name), devices in sorted(devices_by_name.items()):
        if len(devices) > 1:
            skipped.append(
                f"用户 {_user_label(user_id, usernames)} 文件名 {file_name}："
                f"跳过：不同设备同名（不处理），设备：{'、'.join(sorted(devices))}"
            )
    return _Plan(pairs=pairs, skipped_groups=skipped)


async def _check_pair(
    db: aiosqlite.Connection,
    pair: _Pair,
    root_real: str,
    origin_ok: bool,
    has_tables: bool,
    journal_ids: dict[int, list[int]],
    journal_paths: dict[str, list[int]],
) -> Optional[str]:
    """Safety checks of design 12.2 step 4. Returns the skip reason or None."""
    o, n = pair.o, pair.n
    o_id, n_id = int(o["id"]), int(n["id"])
    o_path, n_path = str(o["file_path"]), str(n["file_path"])

    if o["created_at"] and n["created_at"] and str(o["created_at"]) > str(n["created_at"]):
        return "新旧顺序不一致（id 较小的记录创建时间更晚）"

    name = str(o["file_name"])
    stem, suffix = Path(name).stem, Path(name).suffix
    pattern = re.compile(rf"^{re.escape(stem)}_(\d+){re.escape(suffix)}$")
    if (
        Path(o_path).name != name
        or not pattern.match(Path(n_path).name)
        or Path(o_path).parent != Path(n_path).parent
    ):
        return "物理文件不是同目录的「原名 + 原名_N」模式"

    if not _under(o_path, root_real) or not _under(n_path, root_real):
        return "路径不在 media_root 下"

    if pair.username is None:
        return "用户不存在"

    if not origin_ok:
        return "URI 不一致（original_path 不同；确认是同一文件时可加 --allow-uri-mismatch）"

    if await _exists(
        db,
        """SELECT 1 FROM file_records z
           WHERE z.user_id = ? AND z.file_hash = ? AND z.file_path = ? AND z.id NOT IN (?, ?)""",
        (o["user_id"], n["file_hash"], o_path, o_id, n_id),
    ):
        return "目标路径已有同哈希的历史记录"

    if has_tables and await _exists(
        db, "SELECT 1 FROM file_versions WHERE file_record_id IN (?, ?)", (o_id, n_id)
    ):
        return "记录已有历史版本（上线后已被替换过，合并可能让内容倒退）"

    jids = sorted(
        set(journal_ids.get(o_id, []) + journal_ids.get(n_id, [])
            + journal_paths.get(o_path, []) + journal_paths.get(n_path, []))
    )
    if jids:
        return f"存在未完成的版本操作日志（journal id={jids}）"

    if await _exists(
        db,
        "SELECT 1 FROM file_records x WHERE x.reference_to = ? AND x.purged_at IS NULL",
        (o_id,),
    ):
        return "存在引用该记录的条目"

    problems: list[str] = []
    for label, record in (("旧文件", o), ("新文件", n)):
        path = str(record["file_path"])
        if not os.path.isfile(path):
            problems.append(f"{label}不存在")
            continue
        try:
            digest = await asyncio.to_thread(_sha256_file, path)
        except OSError as exc:
            problems.append(f"{label}读取失败（{exc}）")
            continue
        if digest != record["file_hash"]:
            problems.append(f"{label}哈希与记录不符")
    if problems:
        pair.hash_note = "失败：" + "，".join(problems)
        return "文件缺失或哈希校验不一致"
    pair.hash_note = "通过"
    return None


# ---------------------------------------------------------------------------
# Merge (design 12.3)
# ---------------------------------------------------------------------------


async def _migrate_children(
    db: aiosqlite.Connection, user_id: int, o_id: int, n_id: int, n_hash: str, n_path: str
) -> None:
    """extra_ops: move N's analysis rows and references to O, delete N (12.3)."""
    # 1. photo_gps (primary key file_id): N wins when it has a row.
    if await _exists(db, "SELECT 1 FROM photo_gps WHERE file_id = ?", (n_id,)):
        await db.execute("DELETE FROM photo_gps WHERE file_id = ?", (o_id,))
        await db.execute("UPDATE photo_gps SET file_id = ? WHERE file_id = ?", (o_id, n_id))
    # 2. photo_scenes
    if await _exists(db, "SELECT 1 FROM photo_scenes WHERE file_id = ?", (n_id,)):
        await db.execute("DELETE FROM photo_scenes WHERE file_id = ?", (o_id,))
        await db.execute("UPDATE photo_scenes SET file_id = ? WHERE file_id = ?", (o_id, n_id))
    # 3. faces: clearing O recomputes the clusters O's faces belonged to.
    if await _exists(db, "SELECT 1 FROM faces WHERE file_id = ?", (n_id,)):
        await FaceAnalyzer(settings=SimpleNamespace()).clear_file_faces(db, user_id, o_id)
        await db.execute("UPDATE faces SET file_id = ? WHERE file_id = ?", (o_id, n_id))
    # 4. references
    await db.execute(
        "UPDATE file_records SET reference_to = ? WHERE reference_to = ?", (o_id, n_id)
    )
    # 5. N itself
    cursor = await db.execute(
        "DELETE FROM file_records WHERE id = ? AND file_hash = ? AND file_path = ?",
        (n_id, n_hash, n_path),
    )
    if cursor.rowcount != 1:
        raise RuntimeError(f"新记录 id={n_id} 已变化，删除失败")
    # 6. nothing may still point to N
    cursor = await db.execute(
        """SELECT (SELECT COUNT(*) FROM photo_gps WHERE file_id = ?)
                + (SELECT COUNT(*) FROM photo_scenes WHERE file_id = ?)
                + (SELECT COUNT(*) FROM faces WHERE file_id = ?)
                + (SELECT COUNT(*) FROM file_records WHERE reference_to = ?)""",
        (n_id, n_id, n_id, n_id),
    )
    leftover = (await cursor.fetchone())[0]
    if leftover:
        raise RuntimeError(f"合并后仍有 {leftover} 行引用新记录 id={n_id}")


async def _merge_pair(svc: VersionService, pair: _Pair) -> None:
    o, n = pair.o, pair.n
    o_id, n_id = int(o["id"]), int(n["id"])
    user_id = int(o["user_id"])
    n_hash, n_path = str(n["file_hash"]), str(n["file_path"])
    columns = set(n.keys())
    content_values = {c: n[c] for c in _COPIED_COLUMNS if c in columns}

    async def extra_ops(db: aiosqlite.Connection) -> None:
        await _migrate_children(db, user_id, o_id, n_id, n_hash, n_path)

    assert pair.username is not None
    async with path_lock(str(o["file_path"])):
        await svc.replace_record_content(
            op="merge",
            username=pair.username,
            record=o,
            incoming_path=n_path,
            new_hash=n_hash,
            new_size=int(n["file_size"]),
            content_values=content_values,
            media_meta=None,
            extra_ops=extra_ops,
            cleanup_path=n_path,
        )


# ---------------------------------------------------------------------------
# Locks
# ---------------------------------------------------------------------------


def _acquire_presence_lock(media_root: str) -> Optional[int]:
    """Exclusive, non-blocking lock on ``.versions/.server-presence.lock``."""
    versions_dir = os.path.join(os.path.realpath(media_root), VERSIONS_DIRNAME)
    os.makedirs(versions_dir, exist_ok=True)
    fd = os.open(
        os.path.join(versions_dir, SERVER_PRESENCE_LOCK_NAME), os.O_RDWR | os.O_CREAT, 0o644
    )
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        return None
    return fd


def _release_presence_lock(fd: Optional[int]) -> None:
    if fd is None:
        return
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def _print_journal(journal: list[Any], label: str) -> None:
    if not journal:
        return
    print(f"\n{label}：{len(journal)} 条")
    for row in journal:
        print(
            f"  journal id={row['id']} 记录 id={row['file_record_id']} 操作 {row['op']} "
            f"状态 {row['state']} 路径 {row['final_path']}"
        )


def _print_pair(index: int, pair: _Pair, usernames: dict[int, str]) -> None:
    o, n = pair.o, pair.n
    print(
        f"\n[{index}] 用户 {_user_label(int(o['user_id']), usernames)} "
        f"设备 {o['device_name']} 文件名 {o['file_name']}"
    )
    print(f"    旧记录 id={o['id']}：{o['file_path']}")
    print(f"    新记录 id={n['id']}：{n['file_path']}")
    print(f"    URI：{pair.uri_note}")
    print(f"    哈希校验：{pair.hash_note}")
    print(f"    动作：{pair.action}")


def _print_counts(
    title: str,
    deltas: dict[tuple[int, str], int],
    usernames: dict[int, str],
    before: Optional[dict[tuple[int, str], dict[str, int]]] = None,
    after: Optional[dict[tuple[int, str], dict[str, int]]] = None,
) -> None:
    if not deltas:
        print(f"  {title}：已备份计数不变")
        return
    for (user_id, device), delta in sorted(deltas.items()):
        line = f"  {title}：用户 {_user_label(user_id, usernames)} 设备 {device} 的已备份计数减少 {delta}"
        if before is not None and after is not None:
            b, a = before[(user_id, device)], after[(user_id, device)]
            line += (
                f"（{b['backed_up']} → {a['backed_up']}；回收站 {b['trashed']} → {a['trashed']}，"
                f"已清除 {b['purged']} → {a['purged']}）"
            )
        print(line)


# ---------------------------------------------------------------------------
# Entry points
# ---------------------------------------------------------------------------


def _validate(args: argparse.Namespace) -> Optional[str]:
    if not args.db or not args.media_root:
        return MISSING_ARGS_MESSAGE
    if not os.path.isfile(args.db):
        return f"数据库文件不存在或不是普通文件：{args.db}"
    if not os.path.isdir(args.media_root):
        return f"media_root 不存在或不是目录：{args.media_root}"
    if args.user is not None:
        try:
            args.user = int(args.user)
        except (TypeError, ValueError):
            return f"--user 必须是整数 user_id：{args.user}"
        if args.user <= 0:
            return f"--user 必须是正整数：{args.user}"
    return None


async def run(args: argparse.Namespace) -> int:
    """Run the dry-run or the merge. Returns the exit code (0 / 1 / 2)."""
    problem = _validate(args)
    if problem is not None:
        _err(problem)
        return EXIT_ENV_ERROR
    db_path = os.path.abspath(args.db)
    media_root = os.path.abspath(args.media_root)
    apply = bool(args.apply)

    if apply:
        db, note = await _open_writable(db_path)
    else:
        db, note = await _open_readonly(db_path)
    if db is None:
        _err(note)
        return EXIT_ENV_ERROR

    lock_fd: Optional[int] = None
    try:
        try:
            has_tables = await _version_tables_present(db)
        except sqlite3.Error as exc:
            _err(f"数据库读取失败：{exc}")
            return EXIT_ENV_ERROR
        if apply and not has_tables:
            _err("目标库缺少版本表，请先用新版本服务端启动一次（或在本地测试库上运行 init_db）")
            return EXIT_ENV_ERROR

        if apply:
            try:
                lock_fd = _acquire_presence_lock(media_root)
            except OSError as exc:
                _err(f"无法创建或打开在场锁文件：{exc}")
                return EXIT_ENV_ERROR
            if lock_fd is None:
                _err("检测到服务端或另一个修复脚本正在使用该 media_root，请先停止服务端后再执行 --apply")
                return EXIT_ENV_ERROR

        print(f"数据库：{db_path}")
        print(f"media_root：{media_root}")
        print("模式：apply（实际合并）" if apply else "模式：dry-run（只读，不修改任何数据）")
        if note:
            print(note)
        if apply:
            print("在场锁：已获取")
        if not has_tables:
            print("注意：目标库尚未建版本表（新版本服务端尚未在此库上启动过），按空表处理")
        if args.user is not None or args.device is not None:
            print(f"筛选：用户 id={args.user if args.user is not None else '全部'}；"
                  f"设备 {args.device if args.device is not None else '全部'}")

        try:
            return await _run_with_db(db, args, db_path, media_root, apply, has_tables)
        except sqlite3.Error as exc:
            _err(f"数据库读取失败：{exc}")
            return EXIT_ENV_ERROR
    finally:
        await db.close()
        _release_presence_lock(lock_fd)


async def _run_with_db(
    db: aiosqlite.Connection,
    args: argparse.Namespace,
    db_path: str,
    media_root: str,
    apply: bool,
    has_tables: bool,
) -> int:
    svc: Optional[VersionService] = None
    if apply:
        svc = VersionService(db, media_root, role="script")
        recovered = await svc.recover_pending()
        print(f"启动恢复：已处理遗留版本操作日志 {recovered} 条")

    journal = await _journal_rows(db, has_tables)
    if apply:
        _print_journal(journal, "仍未解决的版本操作日志（相关记录本次跳过）")
    else:
        _print_journal(journal, "遗留版本操作日志（待恢复，需 --apply 或服务端处理；相关记录本次跳过）")

    usernames = await _usernames(db)
    plan = await _collect(db, args, media_root, has_tables, journal, usernames)
    before = await _status_counts(db, args)

    mergeable = [p for p in plan.pairs if p.mergeable]
    expected: dict[tuple[int, str], int] = defaultdict(int)
    for pair in mergeable:
        expected[(int(pair.o["user_id"]), pair.o["device_name"])] += 1

    succeeded = failed = 0
    commit_unknown = False
    for index, pair in enumerate(plan.pairs, start=1):
        if not pair.mergeable:
            pair.action = f"跳过：{pair.skip_reason}"
        elif not apply:
            pair.action = "将合并"
        else:
            assert svc is not None
            try:
                await _merge_pair(svc, pair)
            except VersionReplaceError as exc:
                failed += 1
                commit_unknown = commit_unknown or exc.message == _COMMIT_UNKNOWN_MESSAGE
                pair.action = f"失败：{exc.code}：{exc.message}（这一对已回滚，保持原样）"
            except NeedsReclassify as exc:
                failed += 1
                pair.action = f"失败：记录在合并前发生变化（{exc}），这一对未修改"
            except Exception as exc:  # noqa: BLE001 - keep the batch going
                failed += 1
                pair.action = f"失败：{exc!r}"
            else:
                succeeded += 1
                pair.action = "已合并"
        _print_pair(index, pair, usernames)

    if plan.skipped_groups:
        print("\n其他跳过项：")
        for line in plan.skipped_groups:
            print(f"  {line}")

    if commit_unknown:
        await _recover_after_unknown_commit(db_path, media_root)

    skipped = sum(1 for p in plan.pairs if not p.mergeable) + len(plan.skipped_groups)
    print("\n汇总：")
    print(f"  待合并：{len(mergeable)} 对")
    if apply:
        print(f"  成功：{succeeded} 对")
    print(f"  跳过：{skipped} 项")
    if apply:
        print(f"  失败：{failed} 对")
    _print_counts("预计", dict(expected), usernames)
    if apply:
        after = await _status_counts(db, args)
        actual = {
            key: before[key]["backed_up"] - after[key]["backed_up"]
            for key in before
            if before[key]["backed_up"] != after[key]["backed_up"]
        }
        _print_counts("实际", actual, usernames, before, after)
    else:
        print("（dry-run：未修改任何数据；核对无误后加 --apply 执行，执行前必须先停止服务端）")
    return EXIT_PAIR_FAILED if failed else EXIT_OK


async def _recover_after_unknown_commit(db_path: str, media_root: str) -> None:
    """A COMMIT outcome was unknown: run recovery once more on a fresh connection."""
    print("\n有合并的提交结果不确定，正在用新连接重新执行恢复……")
    try:
        conn = await _create_connection(db_path)
    except sqlite3.Error as exc:
        _err(f"恢复连接打开失败：{exc}；请稍后用 --apply 重新运行或启动服务端处理")
        return
    try:
        recovered = await VersionService(conn, media_root, role="script").recover_pending()
        print(f"  已处理版本操作日志 {recovered} 条")
    except Exception as exc:  # noqa: BLE001
        _err(f"恢复失败：{exc!r}；请稍后用 --apply 重新运行或启动服务端处理")
    finally:
        await conn.close()


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
