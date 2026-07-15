#!/usr/bin/env python3
"""Report how many stored photos carry usable GPS coordinates.

A quick diagnostic for the「地点」dimension: it walks the user's live image
records, opens each with the analysis pipeline's HEIC-aware decoder, and reports
how many actually have parseable GPS coordinates versus an empty GPS block or
no GPS at all. It also cross-checks the ``photo_gps`` table.

Usage (from the server/ directory, using the project venv):

    python -m scripts.gps_coverage                # all users
    python -m scripts.gps_coverage --user 3       # one user
    python -m scripts.gps_coverage --show 20      # print up to 20 GPS samples

Notes:
- "有 GPS 块但为空" means the camera wrote a GPSInfo IFD placeholder but no real
  location (location services were off, or coordinates were stripped) — this is
  common and correctly yields no place result.
- HEIC files are only readable when ``pillow-heif`` is installed; otherwise they
  are reported as "无法打开".
- Videos and non-images are skipped.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from pathlib import Path

# Ensure the project root (server/) is importable.
_SERVER_ROOT = Path(__file__).resolve().parent.parent
if str(_SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVER_ROOT))

from app.core.config import get_settings  # noqa: E402


def _normalize_db_path(database_url: str) -> str:
    if database_url.startswith("sqlite+aiosqlite://"):
        return database_url[len("sqlite+aiosqlite://"):]
    if database_url.startswith("sqlite://"):
        return database_url[len("sqlite://"):]
    return database_url


def _resolve_path(conn: sqlite3.Connection, row: sqlite3.Row) -> str | None:
    if row["is_reference"] and row["reference_to"] is not None:
        src = conn.execute(
            "SELECT file_path FROM file_records WHERE id = ?",
            (row["reference_to"],),
        ).fetchone()
        return src["file_path"] if src else None
    return row["file_path"]


def report(db_path: str, user_id: int | None, show: int) -> None:
    if not os.path.exists(db_path):
        print(f"数据库文件不存在: {db_path}", file=sys.stderr)
        sys.exit(1)

    # Imported here so the HEIC opener registers and GPS parsing is reused.
    from app.services.image_utils import heic_supported, open_image
    from app.services.place_analyzer import _GPS_INFO_IFD_TAG, parse_gps_ifd

    print(f"HEIC 支持: {'是' if heic_supported() else '否 (未安装 pillow-heif)'}")

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    where = ["purged_at IS NULL", "deleted_at IS NULL"]
    params: list[object] = []
    if user_id is not None:
        where.append("user_id = ?")
        params.append(user_id)
    rows = conn.execute(
        f"SELECT id, file_path, file_name, is_reference, reference_to, media_type "
        f"FROM file_records WHERE {' AND '.join(where)} ORDER BY id",
        params,
    ).fetchall()

    total = len(rows)
    videos = with_coords = empty_block = no_gps = unreadable = 0
    samples: list[tuple] = []

    for r in rows:
        if (r["media_type"] or "").lower() == "video":
            videos += 1
            continue
        path = _resolve_path(conn, r)
        if not path or not os.path.exists(path):
            unreadable += 1
            continue
        try:
            with open_image(path) as img:
                exif = img.getexif()
                gps = None
                if exif:
                    try:
                        gps = exif.get_ifd(_GPS_INFO_IFD_TAG)
                    except Exception:
                        gps = None
                    if not gps:
                        gps = exif.get(_GPS_INFO_IFD_TAG)
        except Exception:
            unreadable += 1
            continue

        if not gps:
            no_gps += 1
            continue
        coords = parse_gps_ifd(dict(gps))
        if coords is None:
            empty_block += 1
        else:
            with_coords += 1
            if len(samples) < show:
                samples.append((r["file_name"], round(coords[0], 6), round(coords[1], 6)))

    pg = conn.execute("SELECT COUNT(*) FROM photo_gps").fetchone()[0]
    pgc = conn.execute(
        "SELECT COUNT(*) FROM photo_gps WHERE city IS NOT NULL AND city != ''"
    ).fetchone()[0]
    conn.close()

    print(f"\n在库记录总数        : {total}")
    print(f"  视频(跳过)        : {videos}")
    print(f"  无法打开/文件缺失  : {unreadable}")
    print(f"  无 GPS 块          : {no_gps}")
    print(f"  有 GPS 块但为空    : {empty_block}  (拍摄时未开定位/被抹除)")
    print(f"  有可用 GPS 坐标    : {with_coords}")
    print(f"\nphoto_gps 已落库    : {pg} 条 (其中含城市 {pgc} 条)")

    if samples:
        print("\n示例(文件名 -> 纬度, 经度):")
        for name, lat, lon in samples:
            print(f"  {name} -> {lat}, {lon}")
    elif with_coords == 0:
        print("\n没有任何照片带可用 GPS 坐标，因此「地点」为空属正常。")


def main() -> None:
    parser = argparse.ArgumentParser(description="统计带 GPS 坐标的照片数量")
    parser.add_argument("--user", type=int, default=None, help="仅统计指定 user_id")
    parser.add_argument("--show", type=int, default=10, help="打印的 GPS 样本数量上限")
    parser.add_argument(
        "--db", default=None, help="数据库文件路径（默认取自服务端配置）"
    )
    args = parser.parse_args()

    db_path = _normalize_db_path(args.db or get_settings().database_url)
    print(f"使用数据库: {db_path}")
    report(db_path, user_id=args.user, show=args.show)


if __name__ == "__main__":
    main()
