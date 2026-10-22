"""Shared path conventions of a user's library on disk.

Every ``file_records.file_path`` is an absolute path string produced by
:meth:`StoragePathEngine.resolve_path` plus a file name. The helpers here are
the single place that maps such strings onto the per-user layout:

- ``{media_root}/{username}``                 user root (library)
- ``{media_root}/{username}/.trash/{rel}``    a trashed in-library file, ``rel``
  being its path relative to the user root (the trash mirrors the library)
- ``{media_root}/{username}/.trash/.external/{abs}``  a trashed file whose
  original path (custom storage path) is outside the user root; ``abs`` is the
  original absolute path without its leading ``/``

All path math is lexical string manipulation (never ``resolve()``), so it
matches exactly how the stored strings were produced. This module has no
``app`` imports, so any service can use it without import cycles.
"""

from __future__ import annotations

import re
from typing import Optional

TRASH_DIR_NAME = ".trash"
# First segment under the trash root for files that lived outside the user root.
TRASH_EXTERNAL_DIR = ".external"

_SQL_COLUMN_RE = re.compile(r"^[a-z_.]+$")


def user_root(media_root: str, username: str) -> str:
    """``{media_root}/{username}`` without a trailing slash."""
    return f"{media_root.rstrip('/')}/{username}"


def trash_root(media_root: str, username: str) -> str:
    """``{media_root}/{username}/.trash`` without a trailing slash."""
    return f"{user_root(media_root, username)}/{TRASH_DIR_NAME}"


def _check_absolute(path: str) -> None:
    if not path.startswith("/"):
        raise ValueError(f"Expected an absolute path: {path!r}")
    if any(part in (".", "..") for part in path.split("/")):
        raise ValueError(f"Path must not contain '.' or '..' components: {path!r}")


def trash_path_for(media_root: str, username: str, file_path: str) -> Optional[str]:
    """Where ``file_path`` goes when it is moved to the user's trash.

    Returns None when ``file_path`` already lies inside the trash root (nothing
    to move). Raises ValueError for a relative path or one with ``.``/``..``.
    """
    _check_absolute(file_path)
    root = user_root(media_root, username)
    troot = trash_root(media_root, username)
    if file_path.startswith(troot + "/"):
        return None
    if file_path.startswith(root + "/"):
        rel = file_path[len(root) + 1:]
        first = rel.split("/", 1)[0]
        # An in-library top-level ".external" would collide with the external
        # namespace in the trash; store it in the external form instead.
        if first != TRASH_EXTERNAL_DIR:
            return f"{troot}/{rel}"
    return f"{troot}/{TRASH_EXTERNAL_DIR}/{file_path.lstrip('/')}"


def original_path_for(media_root: str, username: str, trash_path: str) -> Optional[str]:
    """Inverse of :func:`trash_path_for`.

    Returns None when ``trash_path`` is not inside the trash root (a record
    marked deleted with its file left in place). Raises ValueError for a
    relative path or one with ``.``/``..``.
    """
    _check_absolute(trash_path)
    troot = trash_root(media_root, username)
    if not trash_path.startswith(troot + "/"):
        return None
    rel = trash_path[len(troot) + 1:]
    external_prefix = TRASH_EXTERNAL_DIR + "/"
    if rel.startswith(external_prefix):
        return "/" + rel[len(external_prefix):]
    return f"{user_root(media_root, username)}/{rel}"


def path_prefix_range(prefix: str) -> tuple[str, str]:
    """``(lo, hi)`` so that ``lo <= p < hi`` iff ``p`` starts with ``prefix``.

    ``prefix`` must end with ``/``; ``hi`` replaces that slash with ``0``, the
    character right after ``/``. With SQLite's default BINARY collation the
    comparison is a case-sensitive memcmp, so ``_``, ``%``, letter case and
    non-ASCII characters are all matched literally (unlike ``LIKE``).
    """
    if not prefix.endswith("/"):
        # A wrong range on a delete path is destructive: refuse, never assert.
        raise ValueError(f"Path prefix must end with '/': {prefix!r}")
    return prefix, prefix[:-1] + "0"


def path_prefix_sql(
    column: str, prefix: str, *, direct_children_only: bool = False
) -> tuple[str, list]:
    """``(sql_fragment, params)`` matching ``column`` paths under ``prefix``.

    Recursive by default; with ``direct_children_only`` only paths with no
    further ``/`` after the prefix match. Values are always bound parameters;
    ``column`` must be a plain (optionally qualified) lowercase identifier.
    """
    if not _SQL_COLUMN_RE.match(column):
        raise ValueError(f"Invalid column name: {column!r}")
    lo, hi = path_prefix_range(prefix)
    sql = f"{column} >= ? AND {column} < ?"
    params: list = [lo, hi]
    if direct_children_only:
        # Python len() and SQLite substr() both count code points.
        sql += f" AND instr(substr({column}, ?), '/') = 0"
        params.append(len(prefix) + 1)
    return sql, params
