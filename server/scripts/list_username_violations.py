#!/usr/bin/env python3
"""List existing users whose names break the rule for NEW usernames (read-only).

The username rule (``app.core.validators.validate_username``) only applies to
new accounts; existing users keep working unchanged. This script reports which
existing names would be rejected today, plus names that collide under
case-insensitive comparison, so an operator can decide what to do by hand.

It opens the database with ``mode=ro`` and never touches the media directory:
nothing is renamed, migrated or deleted.

Usage (from the server/ directory, using the project venv):
    python -m scripts.list_username_violations              # configured database
    python -m scripts.list_username_violations --db PATH    # a specific file

Admin accounts named ``admin`` are not reported: the setup wizard may still
create that name for the first administrator.
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path
from typing import Optional

# Ensure the project root (server/) is importable.
_SERVER_ROOT = Path(__file__).resolve().parent.parent
if str(_SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVER_ROOT))

from app.core.validators import validate_username  # noqa: E402


def parse_args(argv: Optional[list[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="只读列出不符合新用户名规则的现有用户（不修改任何数据）"
    )
    parser.add_argument("--db", help="数据库文件路径（默认使用服务器配置）")
    return parser.parse_args(argv)


def _default_db_path() -> str:
    from app.core.config import get_settings

    return get_settings().database_path


def report(db_path: str) -> int:
    path = Path(db_path)
    if not path.is_file():
        print(f"数据库文件不存在: {db_path}", file=sys.stderr)
        return 1

    conn = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        rows = conn.execute(
            "SELECT id, username, is_admin FROM users ORDER BY id"
        ).fetchall()
    finally:
        conn.close()

    print(f"数据库: {path.resolve()}")
    violations = 0
    by_lower: dict[str, list[str]] = {}
    for user_id, username, is_admin in rows:
        by_lower.setdefault(username.lower(), []).append(username)
        message = validate_username(username, bootstrap_admin=bool(is_admin))
        if message:
            violations += 1
            print(f"id={user_id} username={username!r} 原因：{message}")

    for names in by_lower.values():
        if len(names) > 1:
            print("大小写冲突：" + ", ".join(repr(n) for n in names))

    print(f"共 {len(rows)} 个用户，{violations} 个不符合新规则（仅报告，不修改任何数据）")
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    return report(args.db or _default_db_path())


if __name__ == "__main__":
    sys.exit(main())
