"""scripts/list_username_violations.py: read-only report of legacy usernames."""

import hashlib
import os
import sqlite3

from app.core.database import init_db
from app.core.validators import USERNAME_RESERVED_MESSAGE, USERNAME_RULE_MESSAGE
from scripts import list_username_violations as luv


def _sha256(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


async def test_reports_violations_without_modifying_the_db(tmp_path, capsys):
    db_path = str(tmp_path / "test.db")
    await init_db(db_path)
    conn = sqlite3.connect(db_path)
    conn.executemany(
        "INSERT INTO users (username, password_hash, is_admin) VALUES (?, 'x', ?)",
        [
            ("huo", 0), ("admin", 1), ("Admin2", 0), ("霍", 0),
            ("Alice", 0), ("alice", 0), ("con", 0),
        ],
    )
    conn.commit()
    conn.close()
    before = (_sha256(db_path), os.stat(db_path).st_mtime_ns)

    assert luv.main(["--db", db_path]) == 0
    out = capsys.readouterr().out

    lines = out.splitlines()
    violation_lines = [line for line in lines if line.startswith("id=")]
    reported = {line.split("username=")[1].split(" ")[0] for line in violation_lines}
    assert reported == {"'Admin2'", "'霍'", "'Alice'", "'con'"}
    assert f"username='霍' 原因：{USERNAME_RULE_MESSAGE}" in out
    assert f"username='con' 原因：{USERNAME_RESERVED_MESSAGE}" in out
    assert "大小写冲突：'Alice', 'alice'" in out
    assert "共 7 个用户，4 个不符合新规则" in out
    assert (_sha256(db_path), os.stat(db_path).st_mtime_ns) == before


def test_missing_db_returns_1(tmp_path, capsys):
    assert luv.main(["--db", str(tmp_path / "missing.db")]) == 1
    assert "数据库文件不存在" in capsys.readouterr().err
    assert not (tmp_path / "missing.db").exists()
