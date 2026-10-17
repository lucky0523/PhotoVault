"""File versions: in-place replacement of a record's content with history.

When the same device uploads new bytes for the same file (same user, device,
path and MediaStore/PhotoKit identifier), the existing ``file_records`` row is
updated in place: the id stays, the physical file stays at its path, and the
previous content is kept for ``VERSION_RETENTION_DAYS`` in ``file_versions``
plus ``{media_root}/.versions/<record_id>/``. Dedup, statistics and browsing
only read ``file_records`` and never see history.

Atomicity (design section 5):

* one switch point, a same-directory ``os.replace(staging, final_path)``;
* before it, the old bytes exist twice: ``version_path`` (history) and
  ``rollback_path`` next to ``final_path`` (rollback is always a same-volume
  rename);
* the database switch happens in one ``BEGIN IMMEDIATE`` transaction with CAS
  on both the record and the journal row; an uncertain COMMIT is probed before
  anything is compensated;
* ``file_version_journal`` is committed before any file is created and deleted
  only after every temporary file is gone, so a crash at any point leaves rows
  that ``recover_pending`` / ``recover_for_path`` can converge.

Runtime premises this module relies on (verify on upgrades):

1. aiosqlite runs every call of a connection on one worker thread in FIFO
   order and executes queued calls even if the awaiting future was cancelled.
   Hence a COMMIT that was queued always runs, and ``_probe_commit`` always
   runs after it. ``requirements.txt`` does not pin aiosqlite; the F5b test in
   ``tests/test_version_service.py`` is the regression guard.
2. Every COMMIT is durable: connections use ``PRAGMA synchronous=FULL``
   (``app.core.database._create_connection`` sets it explicitly). With
   ``NORMAL`` in WAL mode a power loss could drop the journal row of an
   operation whose files were already switched.

Correctness does not depend on running a single worker: ``path_locks`` is
only an in-process optimisation; across processes the record/journal CAS, the
fcntl hash lock, journal leases with owner tokens, and the two lock files in
``.versions/`` (server presence, maintenance) do the work.
"""

from __future__ import annotations

import asyncio
import errno
import fcntl
import glob
import hashlib
import logging
import os
import re
import shutil
import socket
import sqlite3
import time
import uuid
from collections.abc import Awaitable, Callable, Iterable, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Literal, NamedTuple, Optional

import aiosqlite

from app.core.config import get_settings
from app.core.database import _create_connection
from app.services.file_browse_service import FileBrowseService
from app.services.media_metadata import MediaMetadata, apply_media_metadata
from app.services.path_locks import path_lock

logger = logging.getLogger("photovault.versions")

VERSION_RETENTION_DAYS = 30
JOURNAL_LEASE_SECONDS = 30 * 60
VERSIONS_DIRNAME = ".versions"
SERVER_PRESENCE_LOCK_NAME = ".server-presence.lock"
MAINTENANCE_LOCK_NAME = ".maintenance.lock"
FILE_LOCK_ATTEMPTS = 3
FILE_LOCK_RETRY_SECONDS = 0.2
PURGE_BATCH_SIZE = 500
# Files created by an in-flight operation have a fresh ctime (link and copy
# both update it), so sweeps never touch anything younger than this.
ORPHAN_MIN_AGE_SECONDS = 24 * 3600

# Generated once per process. pid alone is not enough: a restarted container
# often gets the same pid (1, 7, ...) as the process that died.
PROCESS_TOKEN = uuid.uuid4().hex[:12]
OWNER = f"{socket.gethostname()}:{os.getpid()}:{PROCESS_TOKEN}"

_HASH8_RE = re.compile(r"^[0-9a-f]{8}$")
# Names the maintenance sweeps may delete (design 5.2 and 10; gate #3: no
# restore files).
_VERSION_FILE_RE = re.compile(r"^\d{8}T\d{6}Z_[0-9a-f]{8}(\.[^/.]+)?(\.part)?$")
_PATH_TEMP_RE = re.compile(r"^\.pv-(staging|rollback)-[0-9a-f]{12}(\.[^/]*)?(\.part)?$")
_COLUMN_RE = re.compile(r"^[a-z_][a-z0-9_]*$")
_FORBIDDEN_CONTENT_COLUMNS = frozenset(
    {"id", "user_id", "file_path", "deleted_at", "deleted_batch_id", "purged_at"}
)
# Hard links unsupported (other volume, filesystem without links): copy instead.
_LINK_FALLBACK_ERRNOS = frozenset(
    {errno.EXDEV, errno.EPERM, errno.ENOTSUP, errno.EOPNOTSUPP, errno.EMLINK}
)

# Classification query (design section 4). ``has_live_refs`` uses
# idx_file_records_reference_to, ``new_hash_taken`` idx_file_hash, the main
# query idx_file_records_user_path.
_CANDIDATE_SQL = """
SELECT r.*,
       EXISTS (SELECT 1 FROM file_records x
               WHERE x.reference_to = r.id AND x.purged_at IS NULL) AS has_live_refs,
       EXISTS (SELECT 1 FROM file_records z
               WHERE z.user_id = r.user_id AND z.file_hash = :new_hash
                 AND z.file_path = r.file_path AND z.id != r.id) AS new_hash_taken
FROM file_records r
WHERE r.user_id = :user_id AND r.file_path = :final_path
  AND (r.is_reference = 0 OR r.is_reference IS NULL)
  AND r.deleted_at IS NULL AND r.purged_at IS NULL
"""

_NEW_HASH_TAKEN_SQL = """
SELECT EXISTS (SELECT 1 FROM file_records z
               WHERE z.user_id = ? AND z.file_hash = ? AND z.file_path = ? AND z.id != ?)
"""


# ---------------------------------------------------------------------------
# Small helpers (module level so tests can patch them)
# ---------------------------------------------------------------------------


def _utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def _iso(dt: datetime) -> str:
    return dt.isoformat()


def _short(file_hash: Optional[str]) -> str:
    return (file_hash or "")[:12]


def _same_origin(a_device: str, a_origin: str, b_device: str, b_origin: str) -> bool:
    """Same device (raw ``device_name``) and same, non-empty ``original_path``.

    Shared by upload classification and the merge script so both use one
    definition of "the same file on the same phone". An empty origin means
    "unknown source" and never matches.
    """
    if not a_origin or not b_origin:
        return False
    return a_device == b_device and a_origin == b_origin


def _fsync_file(path: str) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _fsync_dir(path: str) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _fsync_dir_best_effort(path: str) -> None:
    try:
        _fsync_dir(path)
    except OSError as exc:
        logger.debug("fsync of directory %s failed: %s", path, exc)


def _copy_fsync(src: str, dst: str) -> None:
    """Copy ``src`` to ``dst`` so that ``dst``, once it exists, is complete.

    Writes ``dst + ".part"``, fsyncs it, renames it into place, then fsyncs
    the directory (best effort). Removes its own ``.part`` on failure.
    """
    part = dst + ".part"
    try:
        shutil.copy2(src, part)
        _fsync_file(part)
        os.replace(part, dst)
    except BaseException:
        try:
            os.unlink(part)
        except OSError:
            pass
        raise
    _fsync_dir_best_effort(os.path.dirname(dst))


def _link_or_copy(src: str, dst: str) -> None:
    """Hard-link ``src`` to ``dst``; copy where hard links are unsupported.

    Never overwrites: an existing ``dst`` raises ``FileExistsError``.
    """
    if os.path.lexists(dst):
        raise FileExistsError(errno.EEXIST, "target already exists", dst)
    try:
        os.link(src, dst)
    except OSError as exc:
        if exc.errno not in _LINK_FALLBACK_ERRNOS:
            raise
        _copy_fsync(src, dst)


def _sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _unlink_quiet(path: str) -> bool:
    """Remove ``path`` if present. True when it is gone afterwards."""
    try:
        os.unlink(path)
    except FileNotFoundError:
        return True
    except OSError as exc:
        logger.warning("could not remove %s: %s", path, exc)
        return False
    return True


def _temp_paths(row: Mapping[str, Any], *, keep_version: bool = True) -> list[str]:
    """Temporary files a journal row has registered (gate #4).

    staging and rollback plus their ``.part``; with ``keep_version=False``
    also ``version_path`` and its ``.part``.
    """
    paths: list[str] = []
    for key in ("staging_path", "rollback_path"):
        value = row[key]
        if value:
            paths.extend((value, value + ".part"))
    if not keep_version and row["version_path"]:
        paths.extend((row["version_path"], row["version_path"] + ".part"))
    return paths


def _versions_root(media_root: str) -> str:
    return os.path.join(os.path.realpath(media_root), VERSIONS_DIRNAME)


def _resolve_version_file(media_root: str, path: str) -> Optional[str]:
    """Return the resolved path if it is ``.versions/<digits>/<name>``, else None.

    Only the parent directory is resolved, so a symlink named like a version
    file is removed itself and never its target.
    """
    root = _versions_root(media_root)
    resolved = os.path.join(
        os.path.realpath(os.path.dirname(path)), os.path.basename(path)
    )
    rel = os.path.relpath(resolved, root)
    parts = rel.split(os.sep)
    if len(parts) != 2 or not parts[0].isdigit() or parts[1] in ("", ".", ".."):
        return None
    return resolved


def _parse_owner(owner: str) -> Optional[tuple[str, int, str]]:
    parts = (owner or "").rsplit(":", 2)
    if len(parts) != 3:
        return None
    try:
        pid = int(parts[1])
    except ValueError:
        return None
    return parts[0], pid, parts[2]


def _lease_expired(created_at: Optional[str]) -> bool:
    if not created_at:
        return True
    try:
        created = datetime.fromisoformat(str(created_at))
    except ValueError:
        return True
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    return created < datetime.now(timezone.utc) - timedelta(seconds=JOURNAL_LEASE_SECONDS)


# ---------------------------------------------------------------------------
# Public types
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Decision:
    """Result of ``classify_target`` (design section 4)."""

    kind: Literal["legacy", "same_content", "replace"]
    record: Optional[aiosqlite.Row]
    force_suffix: bool = False
    reason: str = ""


class VersionReplaceError(Exception):
    """Replacement failed; the record and ``final_path`` are unchanged.

    ``code``: ``busy``/``conflict`` (HTTP 409) or ``io``/``db`` (HTTP 500).
    ``reclassify`` is True only when the in-transaction record CAS failed (or
    the pre-switch inode check), after full compensation.
    """

    def __init__(
        self,
        code: Literal["busy", "conflict", "io", "db"],
        retryable: bool,
        message: str,
        reclassify: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.message = message
        self.reclassify = reclassify


class NeedsReclassify(Exception):
    """Post-lock read-only CAS failed; nothing was changed."""


class LostClaim(Exception):
    """The journal row was claimed by crash recovery before we could commit."""

    def __init__(self, journal_id: int, claimed_by: Optional[str]) -> None:
        super().__init__(f"journal {journal_id} claimed by {claimed_by}")
        self.journal_id = journal_id
        self.claimed_by = claimed_by


class ReplaceOutcome(NamedTuple):
    record_id: int
    version_id: int
    version_path: str


@dataclass(frozen=True)
class PreparedContent:
    """Content columns and extracted metadata for a replacement (step 1)."""

    content_values: dict[str, object]
    media_meta: Optional[MediaMetadata]


@dataclass
class _ReplaceCtx:
    jid: int
    op: str
    user_id: int
    record_id: int
    old_hash: str
    new_hash: str
    final_path: str
    version_path: str
    rollback_path: str
    staging_path: str
    cleanup_path: Optional[str]
    stage: str = "files"           # "files" | "transaction"
    version_attempted: bool = False
    swapped: bool = False

    def row(self) -> dict[str, Any]:
        return {
            "id": self.jid,
            "op": self.op,
            "user_id": self.user_id,
            "file_record_id": self.record_id,
            "old_hash": self.old_hash,
            "new_hash": self.new_hash,
            "final_path": self.final_path,
            "version_path": self.version_path,
            "rollback_path": self.rollback_path,
            "staging_path": self.staging_path,
            "cleanup_path": self.cleanup_path,
        }


class VersionService:
    """Classification, atomic in-place replacement and crash recovery."""

    def __init__(
        self,
        db: aiosqlite.Connection,
        media_root: str,
        *,
        role: Literal["server", "script"],
    ) -> None:
        if role not in ("server", "script"):
            raise ValueError(f"invalid role {role!r}")
        self._db = db
        self._media_root = media_root
        self._role = role
        # Test hook called at F1 after_version_file, F2 after_version_row,
        # F3 after_swap and F4 before_commit. Never set in production.
        self._fault_hook: Callable[[str], None] = lambda stage: None

    # ------------------------------------------------------------------
    # Classification (design section 4)
    # ------------------------------------------------------------------

    async def classify_target(
        self, user_id: int, session: Mapping[str, Any], final_path: str | os.PathLike[str]
    ) -> Decision:
        """Decide how a completed upload lands on ``final_path``.

        Pre-check outside the fcntl lock; ``replace_record_content`` repeats
        the conditions of the ``replace`` row after locking.
        """
        final_path = str(final_path)
        cursor = await self._db.execute(
            _CANDIDATE_SQL,
            {"user_id": user_id, "final_path": final_path, "new_hash": session["file_hash"]},
        )
        rows = await cursor.fetchall()
        # 1
        if not rows:
            return Decision("legacy", None, reason="no_active_record")
        # 2
        if len(rows) > 1:
            logger.warning("multiple active records at %s; keeping legacy behavior", final_path)
            return Decision("legacy", None, reason="multiple_active_records")
        record = rows[0]
        # 3
        if not os.path.lexists(final_path):
            logger.warning(
                "record %d is active but %s is missing; storing new content with suffix",
                record["id"], final_path,
            )
            return Decision("legacy", record, force_suffix=True, reason="file_missing")
        # 4
        if not os.path.isfile(final_path):
            logger.warning(
                "unexpected entry at %s (record %d); storing new content with suffix",
                final_path, record["id"],
            )
            return Decision("legacy", record, force_suffix=True, reason="not_regular_file")
        # 5
        if record["file_hash"] == session["file_hash"]:
            return Decision("same_content", record, reason="same_content")
        # 6
        if record["has_live_refs"]:
            logger.info("record %d is referenced; keeping legacy suffix behavior", record["id"])
            return Decision("legacy", record, reason="referenced")
        # 7
        if record["new_hash_taken"]:
            logger.info(
                "path %s already has another record with the new hash; "
                "keeping legacy suffix behavior",
                final_path,
            )
            return Decision("legacy", record, reason="new_hash_taken")
        # 8
        same_origin = _same_origin(
            record["device_name"], record["original_path"],
            session["device_name"], session["original_path"],
        )
        name_match = record["file_name"] == session["file_name"]
        if not same_origin or not name_match:
            logger.info(
                "same path but origin differs (device_match=%s, origin_match=%s, "
                "name_match=%s); keeping legacy suffix behavior",
                record["device_name"] == session["device_name"],
                bool(record["original_path"]) and record["original_path"] == session["original_path"],
                name_match,
            )
            return Decision("legacy", record, reason="origin_differs")
        # 9
        return Decision("replace", record, reason="replace")

    # ------------------------------------------------------------------
    # Atomic replacement (design section 5)
    # ------------------------------------------------------------------

    async def replace_record_content(
        self,
        *,
        op: Literal["upload", "merge"],
        username: str,
        record: Mapping[str, Any],
        incoming_path: str,
        new_hash: str,
        new_size: int,
        content_values: dict[str, object],
        media_meta: Optional[MediaMetadata],
        extra_ops: Optional[Callable[[aiosqlite.Connection], Awaitable[None]]] = None,
        cleanup_path: Optional[str] = None,
    ) -> ReplaceOutcome:
        """Replace ``record``'s content with ``incoming_path`` in place.

        Preconditions: the caller holds ``path_lock(record["file_path"])`` and
        the connection has no pending writes. ``content_values`` /
        ``media_meta`` are prepared by the caller (step 1).

        Raises ``VersionReplaceError`` (state unchanged), ``NeedsReclassify``
        (post-lock CAS failed, nothing changed) or ``CancelledError`` (after
        the matching compensation, or after a confirmed commit).
        """
        if self._db.in_transaction:
            logger.error("replace_record_content called with pending writes on the connection")
            raise RuntimeError(
                "replace_record_content requires a connection without pending writes"
            )
        if op not in ("upload", "merge"):
            raise ValueError(f"invalid op {op!r}")

        record_id = int(record["id"])
        user_id = int(record["user_id"])
        old_hash = str(record["file_hash"])
        old_size = int(record["file_size"])
        final_path = str(record["file_path"])

        if self._role == "server":
            try:
                present = ensure_server_presence(self._media_root)
            except OSError as exc:
                raise VersionReplaceError(
                    "io", True, f"cannot open the server presence lock: {exc}"
                ) from exc
            if not present:
                logger.warning(
                    "version maintenance script holds %s; replacement deferred",
                    os.path.join(_versions_root(self._media_root), SERVER_PRESENCE_LOCK_NAME),
                )
                raise VersionReplaceError("busy", True, "version maintenance script is running")

        hash8 = old_hash[:8].lower()
        if not _HASH8_RE.match(hash8):
            logger.error(
                "record %d has a malformed file hash %r; refusing to build a version file name",
                record_id, old_hash,
            )
            raise VersionReplaceError("io", False, f"record {record_id} has a malformed file hash")

        values = self._content_columns(content_values, new_hash, new_size)

        browse = FileBrowseService(self._db, self._media_root)
        await self._acquire_file_lock_with_retry(browse, username, old_hash, record_id)
        try:
            await self._post_lock_cas(record_id, user_id, old_hash, final_path, new_hash)
            return await self._replace_locked(
                op=op,
                username=username,
                record_id=record_id,
                user_id=user_id,
                old_hash=old_hash,
                old_size=old_size,
                final_path=final_path,
                hash8=hash8,
                incoming_path=incoming_path,
                new_hash=new_hash,
                values=values,
                media_meta=media_meta,
                extra_ops=extra_ops,
                cleanup_path=cleanup_path,
            )
        finally:
            browse.release_file_lock(username, old_hash)

    @staticmethod
    def _content_columns(
        content_values: Mapping[str, object], new_hash: str, new_size: int
    ) -> dict[str, object]:
        values = dict(content_values)
        for column in values:
            if not _COLUMN_RE.match(column) or column in _FORBIDDEN_CONTENT_COLUMNS:
                raise ValueError(f"column {column!r} cannot be written by a replacement")
        values["file_hash"] = new_hash
        values["file_size"] = new_size
        return values

    async def _acquire_file_lock_with_retry(
        self, browse: FileBrowseService, username: str, old_hash: str, record_id: int
    ) -> None:
        for attempt in range(FILE_LOCK_ATTEMPTS):
            try:
                if browse.acquire_file_lock(username, old_hash):
                    return
            except OSError as exc:
                raise VersionReplaceError("io", True, f"cannot open the file lock: {exc}") from exc
            if attempt + 1 < FILE_LOCK_ATTEMPTS:
                await asyncio.sleep(FILE_LOCK_RETRY_SECONDS)
        logger.warning(
            "file lock for record %d (hash %s) is busy; replacement deferred",
            record_id, _short(old_hash),
        )
        raise VersionReplaceError("busy", True, "file is currently being processed")

    async def _post_lock_cas(
        self, record_id: int, user_id: int, old_hash: str, final_path: str, new_hash: str
    ) -> None:
        """Read-only re-check of every condition of judgement row 9."""
        cursor = await self._db.execute(
            _CANDIDATE_SQL,
            {"user_id": user_id, "final_path": final_path, "new_hash": new_hash},
        )
        rows = await cursor.fetchall()
        ok = (
            len(rows) == 1
            and rows[0]["id"] == record_id
            and rows[0]["file_hash"] == old_hash
            and not rows[0]["has_live_refs"]
            and not rows[0]["new_hash_taken"]
            and os.path.isfile(final_path)
        )
        if not ok:
            logger.warning(
                "record %d changed before it could be replaced (%s); reclassifying",
                record_id, final_path,
            )
            raise NeedsReclassify(f"record {record_id} changed before replacement")

    async def _replace_locked(
        self,
        *,
        op: str,
        username: str,
        record_id: int,
        user_id: int,
        old_hash: str,
        old_size: int,
        final_path: str,
        hash8: str,
        incoming_path: str,
        new_hash: str,
        values: dict[str, object],
        media_meta: Optional[MediaMetadata],
        extra_ops: Optional[Callable[[aiosqlite.Connection], Awaitable[None]]],
        cleanup_path: Optional[str],
    ) -> ReplaceOutcome:
        # Step 3: one timestamp for the file name, journal and version row.
        now = _utc_now()
        replaced_at = _iso(now)
        expires_at = _iso(now + timedelta(days=VERSION_RETENTION_DAYS))
        target = Path(final_path)
        suffix = target.suffix
        parent = str(target.parent)
        version_dir = os.path.join(_versions_root(self._media_root), str(record_id))
        version_path = os.path.join(version_dir, f"{now:%Y%m%dT%H%M%SZ}_{hash8}{suffix}")
        staging_path = os.path.join(parent, f".pv-staging-{uuid.uuid4().hex[:12]}{suffix}")
        rollback_path = os.path.join(parent, f".pv-rollback-{uuid.uuid4().hex[:12]}{suffix}")

        jid = await self._write_journal(
            op=op, user_id=user_id, record_id=record_id, old_hash=old_hash,
            new_hash=new_hash, final_path=final_path, version_path=version_path,
            rollback_path=rollback_path, staging_path=staging_path,
            cleanup_path=cleanup_path, replaced_at=replaced_at,
        )
        ctx = _ReplaceCtx(
            jid=jid, op=op, user_id=user_id, record_id=record_id, old_hash=old_hash,
            new_hash=new_hash, final_path=final_path, version_path=version_path,
            rollback_path=rollback_path, staging_path=staging_path,
            cleanup_path=cleanup_path,
        )

        try:
            # Step 4a: version file (hard link, or fsynced copy across volumes).
            st = os.stat(final_path)
            old_ident = (st.st_dev, st.st_ino)
            await self._thread_step(os.makedirs, version_dir, 0o755, True)
            if os.path.lexists(version_path):
                raise FileExistsError(errno.EEXIST, "version file already exists", version_path)
            ctx.version_attempted = True
            await self._thread_step(_link_or_copy, final_path, version_path)
            await self._thread_step(_fsync_dir_best_effort, version_dir)
            self._fault_hook("after_version_file")
            # Step 4b: rollback copy next to final_path (same volume).
            await self._thread_step(_link_or_copy, final_path, rollback_path)
            # Step 5: stage the new content next to final_path and make it durable.
            await self._thread_step(_link_or_copy, incoming_path, staging_path)
            await self._thread_step(_fsync_file, staging_path)

            # Step 6: from BEGIN to COMMIT, file operations run synchronously
            # on the event-loop thread, so ``swapped`` is always accurate.
            ctx.stage = "transaction"
            await self._db.execute("BEGIN IMMEDIATE")
            cursor = await self._db.execute(
                "SELECT state, owner FROM file_version_journal WHERE id = ?", (jid,)
            )
            claim = await cursor.fetchone()
            if claim is None or claim["state"] != "prepared" or claim["owner"] != OWNER:
                raise LostClaim(jid, claim["owner"] if claim is not None else None)
            await self._record_cas(ctx, op, cleanup_path)
            cursor = await self._db.execute(
                """INSERT INTO file_versions
                   (file_record_id, user_id, file_hash, file_size, stored_path,
                    replaced_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (record_id, user_id, old_hash, old_size, version_path, replaced_at, expires_at),
            )
            version_id = int(cursor.lastrowid)
            self._fault_hook("after_version_row")
            # Pre-switch inode check; no await between it and the switch.
            try:
                current = os.stat(final_path)
                current_ident: Optional[tuple[int, int]] = (current.st_dev, current.st_ino)
            except FileNotFoundError:
                current_ident = None
            if current_ident != old_ident:
                logger.warning(
                    "%s changed under record %d before the switch; reclassifying",
                    final_path, record_id,
                )
                raise VersionReplaceError(
                    "conflict", True, f"{final_path} changed before the switch", reclassify=True
                )
            os.replace(staging_path, final_path)
            ctx.swapped = True
            _fsync_dir(parent)
            self._fault_hook("after_swap")
            set_sql = ", ".join(f"{column} = ?" for column in values)
            cursor = await self._db.execute(
                f"UPDATE file_records SET {set_sql} WHERE id = ? AND file_hash = ?",
                (*values.values(), record_id, old_hash),
            )
            if cursor.rowcount != 1:
                raise VersionReplaceError("db", True, f"record {record_id} update matched no row")
            if media_meta is not None:
                await apply_media_metadata(self._db, record_id, media_meta)
            if extra_ops is not None:
                await extra_ops(self._db)
            cursor = await self._db.execute(
                """UPDATE file_version_journal SET state = 'committed'
                   WHERE id = ? AND owner = ? AND state = 'prepared'""",
                (jid, OWNER),
            )
            if cursor.rowcount != 1:
                raise VersionReplaceError("db", True, f"journal {jid} could not be marked committed")
            self._fault_hook("before_commit")
        except BaseException as exc:
            to_raise = await self._handle_failure(ctx, exc)
            if to_raise is exc:
                raise
            raise to_raise from exc

        outcome, cancelled = await self._commit_and_confirm(jid)
        if outcome == "committed":
            if cancelled is not None:
                logger.warning(
                    "commit of journal %d confirmed after cancellation; post-commit cleanup deferred",
                    jid,
                )
                raise cancelled
            await self._post_commit_cleanup(ctx, username)
            return ReplaceOutcome(record_id, version_id, version_path)
        if outcome == "not_committed":
            failure: BaseException = cancelled or VersionReplaceError(
                "db", True, f"commit of journal {jid} failed"
            )
            raise await self._handle_failure(ctx, failure)
        logger.error(
            "MANUAL RECOVERY NEEDED commit outcome unknown record_id=%d final_path=%s "
            "rollback_path=%s version_path=%s staging_path=%s journal_id=%d",
            record_id, final_path, rollback_path, version_path, staging_path, jid,
        )
        if self._role == "server":
            schedule_path_recovery(final_path, self._media_root)
        if cancelled is not None:
            raise cancelled
        raise VersionReplaceError("db", True, "commit outcome unknown")

    async def _record_cas(self, ctx: _ReplaceCtx, op: str, cleanup_path: Optional[str]) -> None:
        """In-transaction CAS on the record (and, for merge, on N)."""
        cursor = await self._db.execute(
            """SELECT file_hash, file_path, deleted_at, purged_at FROM file_records
               WHERE id = ? AND user_id = ?""",
            (ctx.record_id, ctx.user_id),
        )
        row = await cursor.fetchone()
        if (
            row is None
            or row["file_hash"] != ctx.old_hash
            or row["file_path"] != ctx.final_path
            or row["deleted_at"] is not None
            or row["purged_at"] is not None
        ):
            logger.warning("record %d changed during replacement; reclassifying", ctx.record_id)
            raise VersionReplaceError(
                "conflict", True, f"record {ctx.record_id} changed", reclassify=True
            )
        cursor = await self._db.execute(
            _NEW_HASH_TAKEN_SQL, (ctx.user_id, ctx.new_hash, ctx.final_path, ctx.record_id)
        )
        if (await cursor.fetchone())[0]:
            logger.warning(
                "path %s gained another record with the new hash; reclassifying", ctx.final_path
            )
            raise VersionReplaceError(
                "conflict", True, f"new hash already used at {ctx.final_path}", reclassify=True
            )
        if op != "merge":
            return
        # merge: O has no versions; N (at cleanup_path) is unchanged and has none.
        cursor = await self._db.execute(
            "SELECT EXISTS (SELECT 1 FROM file_versions WHERE file_record_id = ?)",
            (ctx.record_id,),
        )
        o_has_versions = bool((await cursor.fetchone())[0])
        n_ok = False
        if cleanup_path:
            cursor = await self._db.execute(
                """SELECT r.id,
                          EXISTS (SELECT 1 FROM file_versions v WHERE v.file_record_id = r.id)
                              AS has_versions
                   FROM file_records r
                   WHERE r.user_id = ? AND r.file_path = ? AND r.file_hash = ?
                     AND r.deleted_at IS NULL AND r.purged_at IS NULL""",
                (ctx.user_id, cleanup_path, ctx.new_hash),
            )
            n_rows = await cursor.fetchall()
            n_ok = len(n_rows) == 1 and not n_rows[0]["has_versions"]
        if o_has_versions or not n_ok:
            raise VersionReplaceError(
                "conflict", True, f"merge pair for record {ctx.record_id} changed"
            )

    async def _write_journal(self, **fields: Any) -> int:
        """Step 3: INSERT + commit as one unit (gate #1).

        Runs to completion even if cancelled. On failure or cancellation no
        file exists yet: roll back, delete the row by id if it was inserted,
        then re-raise.
        """
        inserted: list[int] = []

        async def _insert() -> None:
            cursor = await self._db.execute(
                """INSERT INTO file_version_journal
                   (op, state, owner, user_id, file_record_id, old_hash, new_hash,
                    final_path, version_path, rollback_path, staging_path, cleanup_path,
                    replaced_at, created_at)
                   VALUES (?, 'prepared', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    fields["op"], OWNER, fields["user_id"], fields["record_id"],
                    fields["old_hash"], fields["new_hash"], fields["final_path"],
                    fields["version_path"], fields["rollback_path"], fields["staging_path"],
                    fields["cleanup_path"], fields["replaced_at"], fields["replaced_at"],
                ),
            )
            inserted.append(int(cursor.lastrowid))
            await self._db.commit()

        task, cancelled = await self._await_to_completion(_insert())
        failed = task.cancelled() or task.exception() is not None
        if not failed and cancelled is None:
            return inserted[0]

        async def _abort() -> None:
            await self._db.rollback()
            if inserted:
                await self._db.execute(
                    "DELETE FROM file_version_journal WHERE id = ? AND state = 'prepared'",
                    (inserted[0],),
                )
                await self._db.commit()

        abort_task, abort_cancelled = await self._await_to_completion(_abort())
        cancelled = cancelled or abort_cancelled
        if abort_task.cancelled() or abort_task.exception() is not None:
            logger.error(
                "could not remove journal row %s after an aborted write: %r",
                inserted[0] if inserted else None,
                abort_task.exception() if not abort_task.cancelled() else "cancelled",
            )
        if cancelled is not None:
            raise cancelled
        if task.cancelled():
            raise asyncio.CancelledError()
        exc = task.exception()
        if isinstance(exc, sqlite3.Error):
            logger.error(
                "could not write version journal for record %d: %s", fields["record_id"], exc
            )
            raise VersionReplaceError("db", True, f"journal write failed: {exc}") from exc
        assert exc is not None
        raise exc

    # ------------------------------------------------------------------
    # Await helpers
    # ------------------------------------------------------------------

    @staticmethod
    async def _await_to_completion(
        aw: Awaitable[Any],
    ) -> tuple[asyncio.Future, Optional[BaseException]]:
        """Run ``aw`` as its own task and wait until it really finished.

        An external cancellation received meanwhile is remembered (returned)
        instead of interrupting the wait.
        """
        task = asyncio.ensure_future(aw)
        cancelled: Optional[BaseException] = None
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError as exc:
                if not task.done():
                    cancelled = exc
            except Exception:
                pass  # the task finished; its outcome is on the task
        return task, cancelled

    async def _thread_step(self, fn: Callable[..., Any], *args: Any) -> Any:
        """Run ``fn`` in a thread and return only after the thread finished.

        A cancellation received meanwhile is raised once the thread is done,
        so compensation never races a thread that is still writing files.
        """
        task, cancelled = await self._await_to_completion(asyncio.to_thread(fn, *args))
        if cancelled is not None:
            if not task.cancelled() and task.exception() is not None:
                logger.debug("thread step failed after cancellation: %r", task.exception())
            raise cancelled
        return task.result()

    async def _commit_and_confirm(
        self, jid: int
    ) -> tuple[str, Optional[BaseException]]:
        """COMMIT; if it was cancelled or failed, find out what really happened.

        Returns ``("committed" | "not_committed" | "unknown", cancellation)``.
        """
        task, cancelled = await self._await_to_completion(self._db.commit())
        if task.cancelled():
            cancelled = cancelled or asyncio.CancelledError()
        elif task.exception() is None:
            return "committed", cancelled
        else:
            logger.warning("COMMIT of journal %d raised %r; probing outcome", jid, task.exception())
        try:
            probe, probe_cancelled = await self._await_to_completion(self._probe_commit(jid))
            cancelled = cancelled or probe_cancelled
            return probe.result(), cancelled
        except Exception:
            logger.exception("cannot determine commit outcome for journal %d", jid)
            return "unknown", cancelled

    async def _probe_commit(self, jid: int) -> str:
        # FIFO worker queue: these statements run after the queued COMMIT.
        # A COMMIT that failed (e.g. SQLITE_BUSY) leaves the transaction open;
        # rolling back unconditionally is a no-op otherwise.
        await self._db.rollback()
        cursor = await self._db.execute(
            "SELECT state FROM file_version_journal WHERE id = ?", (jid,)
        )
        row = await cursor.fetchone()
        return "committed" if row is not None and row["state"] == "committed" else "not_committed"

    # ------------------------------------------------------------------
    # Compensation (design 5.5)
    # ------------------------------------------------------------------

    async def _handle_failure(self, ctx: _ReplaceCtx, exc: BaseException) -> BaseException:
        """Compensate a not-committed replacement; return the exception to raise."""
        lost = isinstance(exc, LostClaim)
        cancelled = await self._compensate(ctx)

        if isinstance(exc, asyncio.CancelledError):
            if ctx.stage == "files":
                logger.warning(
                    "replacement of record %d cancelled during file preparation; compensated",
                    ctx.record_id,
                )
            else:
                logger.warning(
                    "replacement of record %d cancelled before commit; compensated", ctx.record_id
                )
            return exc
        if cancelled is not None:
            return cancelled
        if lost:
            logger.warning(
                "journal %d of record %d was claimed by recovery (claimed_by=%s); not committing",
                ctx.jid, ctx.record_id, exc.claimed_by,  # type: ignore[union-attr]
            )
            return VersionReplaceError(
                "conflict", True, f"journal {ctx.jid} was claimed by recovery"
            )
        if isinstance(exc, VersionReplaceError):
            result = exc
        elif not isinstance(exc, Exception):
            return exc  # KeyboardInterrupt, SystemExit, ...
        elif isinstance(exc, sqlite3.Error):
            result = VersionReplaceError("db", True, f"database error during replacement: {exc}")
        elif isinstance(exc, OSError):
            result = VersionReplaceError("io", True, f"I/O error during replacement: {exc}")
        else:
            code: Literal["io", "db"] = "db" if ctx.stage == "transaction" else "io"
            result = VersionReplaceError(code, True, f"replacement failed: {exc!r}")
        if result.code in ("busy", "conflict"):
            return result  # already logged as WARNING where detected
        logger.error(
            "replacement of record %d failed (%s): %s record_id=%d final_path=%s "
            "version_path=%s rollback_path=%s journal_id=%d",
            ctx.record_id, result.code, result.message, ctx.record_id, ctx.final_path,
            ctx.version_path, ctx.rollback_path, ctx.jid,
        )
        return result

    async def _compensate(
        self, ctx: _ReplaceCtx
    ) -> Optional[BaseException]:
        """Four-stage compensation. Precondition: the database did not commit.

        The journal row is deleted last, so every temporary file stays
        registered until it is gone. Returns a cancellation received meanwhile.
        """
        cancelled: Optional[BaseException] = None
        parent = os.path.dirname(ctx.final_path)

        # Stage 1: put the old bytes back at final_path, synchronously.
        files_ok = True
        try:
            if ctx.swapped:
                os.replace(ctx.rollback_path, ctx.final_path)
                ctx.swapped = False
                _fsync_dir(parent)
            if os.path.lexists(ctx.staging_path):
                os.unlink(ctx.staging_path)
        except OSError as exc:
            files_ok = False
            logger.error(
                "MANUAL RECOVERY NEEDED record_id=%d final_path=%s rollback_path=%s "
                "version_path=%s staging_path=%s journal_id=%d (%s)",
                ctx.record_id, ctx.final_path, ctx.rollback_path, ctx.version_path,
                ctx.staging_path, ctx.jid, exc,
            )
            if self._role == "server":
                schedule_path_recovery(ctx.final_path, self._media_root)

        # Stage 2: roll back the transaction; is version_path referenced?
        task, stage_cancelled = await self._await_to_completion(
            self._db_rollback_and_probe(ctx.version_path)
        )
        cancelled = cancelled or stage_cancelled
        if task.cancelled() or task.exception() is not None:
            logger.error(
                "rollback after the failed replacement of record %d failed (%r); "
                "journal %d left for recovery",
                ctx.record_id, "cancelled" if task.cancelled() else task.exception(), ctx.jid,
            )
            return cancelled
        if not files_ok:
            return cancelled
        version_referenced = bool(task.result())
        if version_referenced:
            logger.error(
                "version file %s is referenced by a committed row; keeping it", ctx.version_path
            )

        # Stages 3 and 4: temporary files, then the journal row (gate #4).
        # After LostClaim the DELETE cannot match (state/owner changed).
        keep_version = version_referenced or not ctx.version_attempted
        try:
            await self._discard_temps_and_journal(
                ctx.row(), expect_state="prepared", keep_version=keep_version
            )
        except asyncio.CancelledError as exc:
            cancelled = cancelled or exc
        return cancelled

    async def _db_rollback_and_probe(self, version_path: str) -> bool:
        await self._db.rollback()
        cursor = await self._db.execute(
            "SELECT EXISTS (SELECT 1 FROM file_versions WHERE stored_path = ?)", (version_path,)
        )
        return bool((await cursor.fetchone())[0])

    def _delete_temp_files(self, row: Mapping[str, Any], keep_version: bool) -> bool:
        ok = True
        version_path = row["version_path"]
        version_names = {version_path, version_path + ".part"} if version_path else set()
        for path in _temp_paths(row, keep_version=keep_version):
            if path in version_names and _resolve_version_file(self._media_root, path) is None:
                logger.error("refusing to delete version file outside .versions: %s", path)
                ok = False
                continue
            if not _unlink_quiet(path):
                ok = False
        if not keep_version and row["version_path"]:
            try:
                os.rmdir(os.path.dirname(row["version_path"]))
            except OSError:
                pass
        return ok

    async def _delete_journal_row(self, jid: int, expect_state: str) -> bool:
        async def _delete() -> None:
            await self._db.execute(
                "DELETE FROM file_version_journal WHERE id = ? AND owner = ? AND state = ?",
                (jid, OWNER, expect_state),
            )
            await self._db.commit()

        task, cancelled = await self._await_to_completion(_delete())
        ok = not task.cancelled() and task.exception() is None
        if not ok:
            logger.warning(
                "could not delete version journal %d (%r); left for recovery",
                jid, "cancelled" if task.cancelled() else task.exception(),
            )
            rollback_task, rollback_cancelled = await self._await_to_completion(
                self._db.rollback()
            )
            cancelled = cancelled or rollback_cancelled
        if cancelled is not None:
            raise cancelled
        return ok

    async def _discard_temps_and_journal(
        self, row: Mapping[str, Any], expect_state: str, keep_version: bool
    ) -> bool:
        """Delete a row's temporary files, then the row itself (gate #4).

        If any file cannot be deleted, the row is kept and False returned.
        Otherwise ``DELETE … WHERE id=? AND owner=? AND state=?`` and commit.
        Used by compensation, crash recovery and post-commit cleanup.
        """
        if not self._delete_temp_files(row, keep_version):
            logger.warning(
                "could not remove every temporary file of version journal %d; "
                "keeping it for recovery",
                row["id"],
            )
            return False
        return await self._delete_journal_row(row["id"], expect_state)

    # ------------------------------------------------------------------
    # Step 7: post-commit cleanup (best effort)
    # ------------------------------------------------------------------

    async def _post_commit_cleanup(self, ctx: _ReplaceCtx, username: str) -> None:
        row = ctx.row()
        try:
            if await self._remove_cleanup_path(row):
                await self._discard_temps_and_journal(
                    row, expect_state="committed", keep_version=True
                )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning(
                "post-commit cleanup of version journal %d failed; left for maintenance",
                ctx.jid, exc_info=True,
            )
        await self._delete_stale_thumbnails(
            ctx.user_id, username, ctx.old_hash, only_if_unused=True
        )

    async def _remove_cleanup_path(self, row: Mapping[str, Any]) -> bool:
        """Delete a merge's ``cleanup_path`` unless a record still points to it."""
        path = row["cleanup_path"]
        if not path:
            return True
        cursor = await self._db.execute(
            "SELECT EXISTS (SELECT 1 FROM file_records WHERE file_path IN (?, ?))",
            (path, os.path.realpath(path)),
        )
        if (await cursor.fetchone())[0]:
            logger.warning("cleanup path %s still belongs to a record; keeping it", path)
            return True
        return _unlink_quiet(path)

    async def _delete_stale_thumbnails(
        self, user_id: int, username: Optional[str], file_hash: str, *, only_if_unused: bool
    ) -> None:
        """Remove ``.thumbnails/{username}/{file_hash}_*.jpg`` (best effort)."""
        try:
            if username is None:
                cursor = await self._db.execute(
                    "SELECT username FROM users WHERE id = ?", (user_id,)
                )
                user = await cursor.fetchone()
                if user is None:
                    return
                username = user["username"]
            if only_if_unused:
                cursor = await self._db.execute(
                    "SELECT EXISTS (SELECT 1 FROM file_records WHERE user_id = ? AND file_hash = ?)",
                    (user_id, file_hash),
                )
                if (await cursor.fetchone())[0]:
                    return
            pattern = os.path.join(
                self._media_root, ".thumbnails", username, glob.escape(file_hash) + "_*.jpg"
            )
            for path in glob.glob(pattern):
                _unlink_quiet(path)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("could not remove thumbnails of %s", _short(file_hash), exc_info=True)

    # ------------------------------------------------------------------
    # Crash recovery (design 5.6, gate #3)
    # ------------------------------------------------------------------

    async def recover_pending(self) -> int:
        """Recover every journal row this process may handle.

        Takes ``path_lock(final_path)`` per row. Returns the number of rows
        resolved (rolled back or cleaned up and deleted).
        """
        cursor = await self._db.execute("SELECT * FROM file_version_journal ORDER BY id")
        rows = await cursor.fetchall()
        resolved = 0
        for row in rows:
            if row["state"] == "manual" or not self._may_process(row):
                continue
            async with path_lock(row["final_path"]):
                if await self._recover_row(row["id"]) == "resolved":
                    resolved += 1
        cursor = await self._db.execute(
            "SELECT id FROM file_version_journal WHERE state = 'manual' ORDER BY id"
        )
        manual_ids = [r["id"] for r in await cursor.fetchall()]
        if manual_ids:
            logger.warning(
                "%d version journal rows need manual recovery (ids=%s)", len(manual_ids), manual_ids
            )
        return resolved

    async def recover_for_path(self, final_path: str) -> tuple[int, list[int]]:
        """Recover the journal rows of one ``final_path``.

        The caller MUST hold ``path_lock(final_path)`` (``asyncio.Lock`` is
        not re-entrant, so this does not lock). Returns the number and ids of
        rows still unresolved afterwards (``manual`` rows are not counted).
        """
        final_path = str(final_path)
        cursor = await self._db.execute(
            "SELECT * FROM file_version_journal WHERE final_path = ? ORDER BY id", (final_path,)
        )
        for row in await cursor.fetchall():
            if row["state"] != "manual" and self._may_process(row):
                await self._recover_row(row["id"])
        cursor = await self._db.execute(
            """SELECT id FROM file_version_journal
               WHERE final_path = ? AND state != 'manual' ORDER BY id""",
            (final_path,),
        )
        ids = [r["id"] for r in await cursor.fetchall()]
        if self._db.in_transaction:
            await self._db.commit()
        return len(ids), ids

    def _may_process(self, row: Mapping[str, Any]) -> bool:
        """Owner token / lease check (5.6 step 1)."""
        owner = row["owner"]
        if owner == OWNER:
            return True
        parsed = _parse_owner(owner)
        expired = _lease_expired(row["created_at"])
        if parsed is None or parsed[0] != socket.gethostname():
            return expired
        _host, pid, _token = parsed
        if pid == os.getpid():
            return True  # a previous process with our pid: dead
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        except OSError:
            pass  # PermissionError: some live process has this pid
        if expired:
            logger.warning(
                "stale journal %d owned by live pid %d; assuming pid reuse", row["id"], pid
            )
            return True
        return False

    async def _run_db(self, coro: Awaitable[Any]) -> Any:
        """Run a DB unit to completion; roll back on failure; re-raise cancel."""
        task, cancelled = await self._await_to_completion(coro)
        failed = task.cancelled() or task.exception() is not None
        if failed:
            rb, rb_cancelled = await self._await_to_completion(self._db.rollback())
            cancelled = cancelled or rb_cancelled
        if cancelled is not None:
            raise cancelled
        if task.cancelled():
            raise asyncio.CancelledError()
        return task.result()

    async def _recover_row(self, jid: int) -> str:
        """Recover one row. Returns resolved | manual | skipped | failed | gone."""
        cursor = await self._db.execute("SELECT * FROM file_version_journal WHERE id = ?", (jid,))
        seen = await cursor.fetchone()
        if seen is None:
            return "gone"
        if seen["state"] == "manual":
            return "manual"
        if not self._may_process(seen):
            return "skipped"

        async def _claim() -> int:
            await self._db.execute("BEGIN IMMEDIATE")
            cur = await self._db.execute(
                """UPDATE file_version_journal
                   SET state = CASE WHEN state = 'committed' THEN 'committed' ELSE 'recovering' END,
                       owner = ?, claimed_at = ?
                   WHERE id = ? AND owner = ? AND state = ?""",
                (OWNER, _iso(_utc_now()), jid, seen["owner"], seen["state"]),
            )
            await self._db.commit()
            return cur.rowcount

        try:
            claimed = await self._run_db(_claim())
        except sqlite3.Error as exc:
            logger.warning("could not claim version journal %d: %s", jid, exc)
            return "skipped"
        if claimed != 1:
            return "skipped"

        row = dict(seen)
        row["owner"] = OWNER
        row["state"] = "committed" if seen["state"] == "committed" else "recovering"

        if row["state"] == "committed":
            try:
                if await self._remove_cleanup_path(row) and await self._discard_temps_and_journal(
                    row, expect_state="committed", keep_version=True
                ):
                    logger.info(
                        "finished post-commit cleanup of %s/%d for record %d",
                        row["op"], jid, row["file_record_id"],
                    )
                    return "resolved"
            except sqlite3.Error as exc:
                logger.warning("cleanup of committed journal %d failed: %s", jid, exc)
            return "failed"

        try:
            return await self._recover_uncommitted(row)
        except (OSError, sqlite3.Error) as exc:
            logger.error(
                "recovery of version journal %d failed: %s; will retry (record_id=%d "
                "final_path=%s rollback_path=%s version_path=%s staging_path=%s)",
                jid, exc, row["file_record_id"], row["final_path"], row["rollback_path"],
                row["version_path"], row["staging_path"],
            )
            return "failed"

    async def _recover_uncommitted(self, row: dict[str, Any]) -> str:
        jid = row["id"]
        final_path = row["final_path"]
        rollback_path = row["rollback_path"]
        old_hash = row["old_hash"]

        # (a) Database first, files after.
        cursor = await self._db.execute(
            """SELECT file_hash, file_path, deleted_at, purged_at FROM file_records
               WHERE id = ?""",
            (row["file_record_id"],),
        )
        record = await cursor.fetchone()
        if (
            record is None
            or record["deleted_at"] is not None
            or record["purged_at"] is not None
            or record["file_hash"] != old_hash
            or record["file_path"] != final_path
        ):
            await self._mark_manual(row, "record changed after the interrupted operation")
            return "manual"
        cursor = await self._db.execute(
            "SELECT EXISTS (SELECT 1 FROM file_versions WHERE stored_path = ?)",
            (row["version_path"],),
        )
        if (await cursor.fetchone())[0]:
            await self._mark_manual(
                row, "version_path is referenced by a committed version row"
            )
            return "manual"

        # (b) Was final_path switched? (read-only)
        if not os.path.isfile(final_path):
            await self._mark_manual(row, "final_path is missing or not a regular file")
            return "manual"
        swapped: Optional[bool] = None
        if os.path.lexists(rollback_path) and os.path.samefile(rollback_path, final_path):
            swapped = False
        else:
            current = await self._thread_step(_sha256_file, final_path)
            if current == old_hash:
                swapped = False
            elif current == row["new_hash"]:
                swapped = True
        if swapped is None:
            await self._mark_manual(row, "final_path matches neither the old nor the new hash")
            return "manual"

        # (c) Roll back from rollback_path only (gate #3).
        if swapped:
            source_ok = os.path.isfile(rollback_path) and (
                await self._thread_step(_sha256_file, rollback_path) == old_hash
            )
            if not source_ok:
                verdict = await self._describe_version_file(row)
                await self._mark_manual(
                    row, f"switched but rollback_path is unusable; {verdict}"
                )
                return "manual"
            os.replace(rollback_path, final_path)
            _fsync_dir(os.path.dirname(final_path))
            await self._delete_stale_thumbnails(
                row["user_id"], None, old_hash, only_if_unused=False
            )

        # (d) + (e) temporary files, version file, then the row.
        if not await self._discard_temps_and_journal(
            row, expect_state="recovering", keep_version=False
        ):
            logger.error(
                "recovery of version journal %d could not remove its files; will retry", jid
            )
            return "failed"
        logger.info(
            "recovered interrupted %s/%s for record %d (rolled back, swapped=%s)",
            row["op"], jid, row["file_record_id"], swapped,
        )
        return "resolved"

    async def _describe_version_file(self, row: Mapping[str, Any]) -> str:
        version_path = row["version_path"]
        if not version_path or not os.path.isfile(version_path):
            return f"version_path {version_path} is missing"
        digest = await self._thread_step(_sha256_file, version_path)
        if digest == row["old_hash"]:
            return (
                f"version_path {version_path} verified (sha256 matches old_hash) "
                "and can be used for a manual rollback"
            )
        return f"version_path {version_path} does NOT match old_hash (sha256 {_short(digest)})"

    async def _mark_manual(self, row: Mapping[str, Any], reason: str) -> None:
        async def _update() -> None:
            await self._db.execute(
                "UPDATE file_version_journal SET state = 'manual' WHERE id = ? AND owner = ?",
                (row["id"], OWNER),
            )
            await self._db.commit()

        await self._run_db(_update())
        logger.error(
            "MANUAL RECOVERY NEEDED record_id=%d final_path=%s rollback_path=%s "
            "version_path=%s staging_path=%s journal_id=%d: %s",
            row["file_record_id"], row["final_path"], row["rollback_path"],
            row["version_path"], row["staging_path"], row["id"], reason,
        )

    # ------------------------------------------------------------------
    # Periodic maintenance (design section 10, gate #3/#4/#6)
    # ------------------------------------------------------------------

    async def purge_expired(self, *, batch_size: int = PURGE_BATCH_SIZE) -> int:
        """Delete expired versions: the file first, then the row.

        Only touches ``file_versions``. A missing file is logged (WARNING) and
        its row deleted anyway; a file that cannot be deleted or lies outside
        ``.versions/`` (ERROR) keeps its row for the next round. One commit
        per batch. Returns the number of rows deleted.
        """
        now = _iso(_utc_now())
        deleted_rows = 0
        last_id = 0
        while True:
            cursor = await self._db.execute(
                """SELECT id, stored_path, file_record_id FROM file_versions
                   WHERE expires_at <= ? AND id > ? ORDER BY id LIMIT ?""",
                (now, last_id, batch_size),
            )
            rows = await cursor.fetchall()
            if not rows:
                break
            last_id = rows[-1]["id"]
            dirs: set[str] = set()
            for row in rows:
                path = row["stored_path"]
                result = self._unlink_version_file(self._media_root, path)
                if result == "out_of_bounds":
                    continue  # ERROR already logged; keep the row
                if result == "error":
                    logger.error(
                        "could not delete expired version file %s (version_id=%d); "
                        "retrying next round",
                        path, row["id"],
                    )
                    continue
                if result == "missing":
                    logger.warning(
                        "expired version file missing: %s (version_id=%d)", path, row["id"]
                    )
                cursor = await self._db.execute(
                    "DELETE FROM file_versions WHERE id = ?", (row["id"],)
                )
                deleted_rows += max(cursor.rowcount, 0)
                resolved = _resolve_version_file(self._media_root, path)
                if resolved is not None:
                    dirs.add(os.path.dirname(resolved))
            await self._db.commit()
            for directory in dirs:
                try:
                    os.rmdir(directory)
                except OSError:
                    pass
            if len(rows) < batch_size:
                break
        if deleted_rows:
            logger.info("Deleted %d expired file version(s)", deleted_rows)
        return deleted_rows

    async def _path_referenced(self, sql: str, path: str) -> bool:
        cursor = await self._db.execute(sql, (path, os.path.realpath(path)))
        return bool((await cursor.fetchone())[0])

    async def sweep_orphans(self, min_age_seconds: float = ORPHAN_MIN_AGE_SECONDS) -> int:
        """Delete unreferenced files under ``.versions/<digits>/`` (conservative).

        Skipped entirely (ERROR) when a user is named ``.versions`` or
        ``.versions/`` holds anything but digit directories and the two lock
        files. A directory with a subdirectory or an unexpected file name is
        skipped (WARNING). A file is deleted only if its ctime is older than
        ``min_age_seconds`` and no version row, journal row (any state) or
        file record refers to it. Returns the number of files deleted.
        """
        cursor = await self._db.execute(
            "SELECT 1 FROM users WHERE username = ?", (VERSIONS_DIRNAME,)
        )
        if await cursor.fetchone() is not None:
            logger.error(
                "username '%s' collides with the version store; orphan sweep disabled",
                VERSIONS_DIRNAME,
            )
            return 0
        root = _versions_root(self._media_root)
        try:
            entries = sorted(os.scandir(root), key=lambda e: e.name)
        except FileNotFoundError:
            return 0
        except OSError as exc:
            logger.warning("cannot list %s: %s; orphan sweep skipped", root, exc)
            return 0
        version_dirs: list[str] = []
        for entry in entries:
            if entry.name in (SERVER_PRESENCE_LOCK_NAME, MAINTENANCE_LOCK_NAME) and entry.is_file(
                follow_symlinks=False
            ):
                continue
            if entry.name.isdigit() and entry.is_dir(follow_symlinks=False):
                version_dirs.append(entry.path)
                continue
            logger.error(
                "media_root/.versions contains unexpected entry %r; "
                "orphan sweep disabled this round",
                entry.name,
            )
            return 0

        removed = 0
        now = time.time()
        for directory in version_dirs:
            try:
                children = list(os.scandir(directory))
            except OSError as exc:
                logger.warning("cannot list %s: %s; skipped", directory, exc)
                continue
            if any(
                not child.is_file(follow_symlinks=False)
                or not _VERSION_FILE_RE.match(child.name)
                for child in children
            ):
                logger.warning(
                    "%s contains unexpected entries; skipped by the orphan sweep", directory
                )
                continue
            for child in children:
                path = child.path
                try:
                    ctime = os.lstat(path).st_ctime
                except FileNotFoundError:
                    continue
                if now - ctime <= min_age_seconds:
                    continue
                if await self._path_referenced(
                    "SELECT EXISTS (SELECT 1 FROM file_versions WHERE stored_path IN (?, ?))",
                    path,
                ):
                    continue
                base = path[: -len(".part")] if path.endswith(".part") else path
                if await self._path_referenced(
                    """SELECT EXISTS (SELECT 1 FROM file_version_journal
                                      WHERE version_path IN (?, ?))""",
                    base,
                ):
                    continue
                if await self._path_referenced(
                    "SELECT EXISTS (SELECT 1 FROM file_records WHERE file_path IN (?, ?))",
                    path,
                ):
                    continue
                try:
                    os.unlink(path)
                except OSError as exc:
                    logger.warning("could not delete orphan version file %s: %s", path, exc)
                    continue
                removed += 1
                logger.info("Deleted orphan version file %s (ctime=%s)", path, ctime)
            try:
                os.rmdir(directory)
            except OSError:
                pass
        return removed

    async def sweep_path_temps(self, min_age_seconds: float = ORPHAN_MIN_AGE_SECONDS) -> int:
        """Fallback cleanup of ``.pv-staging-*`` / ``.pv-rollback-*`` files.

        Scans, without recursion, only directories where a ``final_path`` can
        be: parents of ``file_records.file_path`` and of journal
        ``final_path``, plus ``upload_sessions.target_path`` (gate #6). A
        matching regular file is deleted only if its ctime is older than
        ``min_age_seconds``, no journal row (any state) registers it
        (``_temp_paths``, gate #4) and no file record points to it. Returns
        the number of files deleted.
        """
        directories: set[str] = set()
        cursor = await self._db.execute("SELECT DISTINCT file_path FROM file_records")
        directories.update(os.path.dirname(r[0]) for r in await cursor.fetchall() if r[0])
        cursor = await self._db.execute("SELECT * FROM file_version_journal")
        journal = await cursor.fetchall()
        directories.update(os.path.dirname(r["final_path"]) for r in journal if r["final_path"])
        cursor = await self._db.execute("SELECT DISTINCT target_path FROM upload_sessions")
        directories.update(str(r[0]).rstrip("/") or "/" for r in await cursor.fetchall() if r[0])

        registered: set[str] = set()
        for row in journal:
            for path in _temp_paths(row):
                registered.add(path)
                registered.add(os.path.realpath(path))

        removed = 0
        now = time.time()
        for directory in sorted(directories):
            try:
                entries = list(os.scandir(directory))
            except OSError as exc:
                logger.debug("sweep_path_temps: cannot list %s: %s", directory, exc)
                continue
            for entry in entries:
                if not _PATH_TEMP_RE.match(entry.name):
                    continue
                if not entry.is_file(follow_symlinks=False):
                    continue
                path = entry.path
                if path in registered or os.path.realpath(path) in registered:
                    continue
                try:
                    ctime = os.lstat(path).st_ctime
                except FileNotFoundError:
                    continue
                if now - ctime <= min_age_seconds:
                    continue
                if await self._path_referenced(
                    "SELECT EXISTS (SELECT 1 FROM file_records WHERE file_path IN (?, ?))",
                    path,
                ):
                    continue
                try:
                    os.unlink(path)
                except OSError as exc:
                    logger.warning("could not delete stale temporary file %s: %s", path, exc)
                    continue
                removed += 1
                logger.info("Deleted stale temporary file %s (ctime=%s)", path, ctime)
        return removed

    # ------------------------------------------------------------------
    # Version file deletion (design section 9)
    # ------------------------------------------------------------------

    @staticmethod
    def _unlink_version_file(
        media_root: str, path: str
    ) -> Literal["deleted", "missing", "error", "out_of_bounds"]:
        """Delete one ``.versions/<record_id>/<name>`` file (bounds-checked)."""
        resolved = _resolve_version_file(media_root, path)
        if resolved is None:
            logger.error(
                "refusing to delete version file outside %s: %s",
                _versions_root(media_root), path,
            )
            return "out_of_bounds"
        try:
            os.unlink(resolved)
        except FileNotFoundError:
            return "missing"
        except OSError as exc:
            logger.debug("unlink of version file %s failed: %s", path, exc)
            return "error"
        return "deleted"

    @staticmethod
    def delete_version_files(media_root: str, paths: Iterable[str]) -> int:
        """Delete version files after their rows were removed; return the count.

        Only ``rmdir``s each file's own ``.versions/<record_id>/`` directory,
        never anything above it (``.versions/`` holds the lock files).
        """
        deleted = 0
        dirs: set[str] = set()
        for path in paths:
            result = VersionService._unlink_version_file(media_root, path)
            if result == "deleted":
                deleted += 1
            elif result == "missing":
                logger.warning("version file already missing: %s", path)
            elif result == "error":
                logger.warning("could not delete version file %s", path)
            if result in ("deleted", "missing"):
                resolved = _resolve_version_file(media_root, path)
                if resolved is not None:
                    dirs.add(os.path.dirname(resolved))
        for directory in dirs:
            try:
                os.rmdir(directory)
            except OSError:
                pass
        return deleted


# ---------------------------------------------------------------------------
# Deferred per-path recovery (COMMIT unknown, compensation stage 1 failure)
# ---------------------------------------------------------------------------

_background_recoveries: set[asyncio.Task] = set()


def schedule_path_recovery(final_path: str, media_root: str) -> Optional[asyncio.Task]:
    """Best-effort ``recover_for_path`` on a fresh connection, in the background.

    Waits for ``path_lock(final_path)``, so it runs once the current request
    released it. Failures are logged; maintenance retries later.
    """
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        logger.warning("no running loop; deferred recovery for %s not scheduled", final_path)
        return None
    task = loop.create_task(
        _deferred_path_recovery(str(final_path), media_root),
        name=f"version-recovery:{final_path}",
    )
    _background_recoveries.add(task)
    task.add_done_callback(_background_recoveries.discard)
    return task


async def _deferred_path_recovery(final_path: str, media_root: str) -> None:
    try:
        async with path_lock(final_path):
            db = await _create_connection(get_settings().database_url)
            try:
                service = VersionService(db, media_root, role="server")
                remaining, ids = await service.recover_for_path(final_path)
                if remaining:
                    logger.warning(
                        "deferred recovery for %s left %d journal rows (ids=%s)",
                        final_path, remaining, ids,
                    )
            finally:
                await db.close()
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.warning(
            "deferred recovery for %s failed; will retry in maintenance", final_path, exc_info=True
        )


# ---------------------------------------------------------------------------
# Inter-process locks in .versions/ (design 5.4)
# ---------------------------------------------------------------------------

_presence_fds: dict[str, int] = {}


def ensure_server_presence(media_root: str) -> bool:
    """Hold a shared lock on ``.versions/.server-presence.lock`` until exit.

    False means the repair script holds it exclusively (``--apply`` running).
    Retried on the next call. Raises ``OSError`` if the directory or the lock
    file cannot be created.
    """
    key = os.path.realpath(media_root)
    if key in _presence_fds:
        return True
    versions_dir = os.path.join(key, VERSIONS_DIRNAME)
    os.makedirs(versions_dir, exist_ok=True)
    fd = os.open(os.path.join(versions_dir, SERVER_PRESENCE_LOCK_NAME), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        return False
    _presence_fds[key] = fd
    return True


@contextmanager
def try_maintenance_lock(media_root: str) -> Iterator[bool]:
    """Non-blocking exclusive lock on ``.versions/.maintenance.lock``.

    Yields whether it was acquired. Unlocks and closes on exit; the lock file
    itself is never deleted (no unlink/recreate inode race).
    """
    versions_dir = _versions_root(media_root)
    os.makedirs(versions_dir, exist_ok=True)
    fd = os.open(os.path.join(versions_dir, MAINTENANCE_LOCK_NAME), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        yield False
        return
    try:
        yield True
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)
