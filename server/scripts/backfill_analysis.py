#!/usr/bin/env python3
"""Backfill place / scene / face analysis for existing file_records.

Newly uploaded photos are analyzed asynchronously right after upload (see the
AnalysisPipeline hooked into the upload path). Files that were uploaded before
the analysis feature existed — or that need re-analysis after a model / geocoding
database was installed or updated — have no rows in ``photo_gps`` /
``photo_scenes`` / ``faces``. This one-off script walks the selected records and
runs :meth:`AnalysisService.analyze_file` for each, populating the analysis
result tables (requirement 7.3).

Usage (from the server/ directory, using the project venv):

    python -m scripts.backfill_analysis               # analyze un-analyzed photos
    python -m scripts.backfill_analysis --dry-run     # report only, no analysis
    python -m scripts.backfill_analysis --reanalyze   # re-analyze ALL photos
    python -m scripts.backfill_analysis --user 3      # limit to one user

Selection modes:
- default: image records that have NO photo_gps AND NO photo_scenes AND NO faces
  rows — i.e. photos not yet analyzed in any dimension.
- ``--reanalyze`` / ``--all``: every non-purged image record, regardless of
  existing results. Use this after installing/updating a model or geocoding DB
  (requirement 7.3 "需重新分析").

Notes:
- Analysis runs through :meth:`AnalysisService.analyze_file`, which resolves the
  physical path, gates each dimension by its FeatureFlag + availability, isolates
  per-dimension failures, and is idempotent — it clears prior results for a
  dimension before writing fresh ones. Re-running is therefore safe and will not
  accumulate duplicate results (requirement 7.5).
- Permanently purged records (purged_at IS NOT NULL) are skipped: their physical
  file no longer exists.
- Reference records (is_reference=1) are analyzed too. A reference points at the
  same physical file as its target but belongs to a different (user_id, file_id),
  so analyzing it populates that user's library. analyze_file resolves the
  reference to the underlying file, so the correct pixels are analyzed for each
  owning user (requirement matching per-user isolation).
- Only image records (media_type = 'image') are selected — videos are not
  analyzed by the pipeline.
"""

from __future__ import annotations

import argparse
import asyncio
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
from app.services.analysis_service import AnalysisService  # noqa: E402


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


def _select_records(
    db_path: str, reanalyze: bool, user_id: int | None
) -> list[tuple[int, int]]:
    """Return the ``(user_id, file_id)`` pairs to analyze.

    Args:
        db_path: Plain sqlite file path.
        reanalyze: When True, select every non-purged image record. When False,
            select only image records lacking results in all three dimensions.
        user_id: Optional user id to restrict the selection.

    Returns:
        A list of ``(user_id, file_id)`` tuples in id order.
    """
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    params: list[object] = []
    where = ["fr.purged_at IS NULL", "fr.media_type = 'image'"]

    if not reanalyze:
        # Un-analyzed in ANY dimension: no photo_gps, photo_scenes or faces rows.
        where.append(
            "NOT EXISTS (SELECT 1 FROM photo_gps g WHERE g.file_id = fr.id)"
        )
        where.append(
            "NOT EXISTS (SELECT 1 FROM photo_scenes s WHERE s.file_id = fr.id)"
        )
        where.append(
            "NOT EXISTS (SELECT 1 FROM faces f WHERE f.file_id = fr.id)"
        )

    if user_id is not None:
        where.append("fr.user_id = ?")
        params.append(user_id)

    sql = (
        "SELECT fr.id AS id, fr.user_id AS user_id "
        "FROM file_records fr "
        f"WHERE {' AND '.join(where)} "
        "ORDER BY fr.id"
    )
    cur = conn.execute(sql, params)
    rows = [(row["user_id"], row["id"]) for row in cur.fetchall()]
    conn.close()
    return rows


async def _run(records: list[tuple[int, int]]) -> tuple[int, int]:
    """Analyze each ``(user_id, file_id)`` pair.

    Returns:
        A ``(analyzed, failed)`` tuple. ``analyze_file`` isolates per-dimension
        failures internally, so a "failed" here means the whole call raised
        (e.g. path resolution / unexpected error).
    """
    service = AnalysisService()
    total = len(records)
    analyzed = 0
    failed = 0

    for i, (user_id, file_id) in enumerate(records, start=1):
        try:
            await service.analyze_file(user_id, file_id)
            analyzed += 1
        except Exception as exc:  # noqa: BLE001 - keep the batch going
            failed += 1
            print(
                f"  分析失败 file_id={file_id} user_id={user_id}: {exc}",
                file=sys.stderr,
            )

        if i % 50 == 0:
            print(f"  已分析 {i}/{total} ...")

    return analyzed, failed


def backfill(
    db_path: str,
    dry_run: bool = False,
    reanalyze: bool = False,
    user_id: int | None = None,
) -> None:
    """Backfill analysis results for the selected records."""
    if not os.path.exists(db_path):
        print(f"数据库文件不存在: {db_path}", file=sys.stderr)
        sys.exit(1)

    records = _select_records(db_path, reanalyze=reanalyze, user_id=user_id)

    mode = "全库重新分析" if reanalyze else "未分析照片"
    scope = f"（用户 {user_id}）" if user_id is not None else ""
    print(f"选择模式: {mode}{scope}")

    total = len(records)
    print(f"待处理记录数: {total}")
    if total == 0:
        print("没有需要分析的记录。")
        return

    if dry_run:
        print("\n[dry-run] 未执行分析。")
        print("\n完成:")
        print(f"  预计分析记录数 : {total}")
        print("  (dry-run 模式，实际未修改数据库)")
        return

    analyzed, failed = asyncio.run(_run(records))

    print("\n完成:")
    print(f"  已完成分析 : {analyzed}")
    print(f"  调用失败   : {failed}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="对存量照片回填人物/地点/场景分析结果"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只统计与预览，不执行分析",
    )
    parser.add_argument(
        "--reanalyze",
        "--all",
        dest="reanalyze",
        action="store_true",
        help="重新分析全部（非清除）照片，忽略已有结果",
    )
    parser.add_argument(
        "--user",
        type=int,
        default=None,
        help="仅处理指定 user_id 的照片",
    )
    parser.add_argument(
        "--db",
        default=None,
        help="数据库文件路径（默认取自服务端配置 database_url）",
    )
    args = parser.parse_args()

    db_path = _normalize_db_path(args.db or get_settings().database_url)
    print(f"使用数据库: {db_path}")
    backfill(
        db_path,
        dry_run=args.dry_run,
        reanalyze=args.reanalyze,
        user_id=args.user,
    )


if __name__ == "__main__":
    main()
