#!/usr/bin/env python3
"""Backfill the focal_length column for existing file_records.

Newly uploaded photos have their focal length extracted from EXIF at upload
time. Files that were uploaded before that feature existed have a NULL
focal_length. This one-off script walks every record whose focal_length is
still NULL, reads the physical image file, extracts the focal length, and
writes it back.

Usage (from the server/ directory, using the project venv):

    python -m scripts.backfill_focal_length            # backfill for real
    python -m scripts.backfill_focal_length --dry-run  # report only, no writes

Notes:
- Reuses UploadService._extract_focal_length so extraction stays consistent
  with the upload path (prefers 35mm-equivalent focal length).
- References (is_reference=1) share the physical file of the record they point
  to, so their focal length is resolved from that source record's file_path.
- Permanently purged records (purged_at IS NOT NULL) are skipped: their
  physical file no longer exists.
- Safe to run multiple times — only NULL focal_length rows are touched.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from pathlib import Path

# Ensure the project root (server/) is importable so `app` resolves when the
# script is run directly rather than via `python -m`.
_SERVER_ROOT = Path(__file__).resolve().parent.parent
if str(_SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVER_ROOT))

from app.core.config import get_settings  # noqa: E402
from app.services.upload_service import UploadService  # noqa: E402


def _normalize_db_path(database_url: str) -> str:
    """Strip SQLAlchemy-style prefixes to get a plain sqlite file path.

    Mirrors the normalization the server applies (see app/main.py and
    app/services/background_tasks.py).
    """
    if database_url.startswith("sqlite+aiosqlite://"):
        return database_url[len("sqlite+aiosqlite://"):]
    if database_url.startswith("sqlite://"):
        return database_url[len("sqlite://"):]
    return database_url


def _resolve_physical_path(
    conn: sqlite3.Connection, row: sqlite3.Row
) -> str | None:
    """Return the on-disk path for a record, following references."""
    if row["is_reference"] and row["reference_to"] is not None:
        cur = conn.execute(
            "SELECT file_path FROM file_records WHERE id = ?",
            (row["reference_to"],),
        )
        src = cur.fetchone()
        return src["file_path"] if src else None
    return row["file_path"]


def backfill(db_path: str, dry_run: bool = False) -> None:
    """Backfill focal_length for all records missing it."""
    if not os.path.exists(db_path):
        print(f"数据库文件不存在: {db_path}", file=sys.stderr)
        sys.exit(1)

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    cur = conn.execute(
        """SELECT id, file_path, is_reference, reference_to
           FROM file_records
           WHERE focal_length IS NULL AND purged_at IS NULL"""
    )
    rows = cur.fetchall()

    total = len(rows)
    print(f"待处理记录数: {total}")
    if total == 0:
        print("没有需要回填的记录。")
        conn.close()
        return

    updated = 0
    no_focal = 0
    missing_file = 0
    updates: list[tuple[float, int]] = []

    for i, row in enumerate(rows, start=1):
        path = _resolve_physical_path(conn, row)
        if not path or not os.path.exists(path):
            missing_file += 1
            continue

        focal = UploadService._extract_focal_length(path)
        if focal is None:
            no_focal += 1
            continue

        updates.append((focal, row["id"]))
        updated += 1

        if i % 200 == 0:
            print(f"  已扫描 {i}/{total} ...")

    if dry_run:
        print("\n[dry-run] 未写入数据库。")
    else:
        conn.executemany(
            "UPDATE file_records SET focal_length = ? WHERE id = ?",
            updates,
        )
        conn.commit()

    conn.close()

    print("\n完成:")
    print(f"  成功提取并回填焦段 : {updated}")
    print(f"  无 EXIF 焦段信息   : {no_focal}")
    print(f"  物理文件缺失/跳过  : {missing_file}")
    if dry_run:
        print("  (dry-run 模式，实际未修改数据库)")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="回填 file_records 表中缺失的 focal_length（焦段）"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只统计与预览，不写入数据库",
    )
    parser.add_argument(
        "--db",
        default=None,
        help="数据库文件路径（默认取自服务端配置 database_url）",
    )
    args = parser.parse_args()

    db_path = _normalize_db_path(args.db or get_settings().database_url)
    print(f"使用数据库: {db_path}")
    backfill(db_path, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
