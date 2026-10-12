"""Lifecycle harness for the fnOS package scripts (packaging/fnos/cmd).

Runs the real scripts with bash against a temp directory tree that stands in for
the TRIM_* directories, in the order fnOS was observed to use. What it cannot
show: the actual order and TRIM_APP_STATUS values on a device; see
``upgrade(uninstall_status=None)`` for the fallback ordering it also covers.
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(shutil.which("bash") is None, reason="needs bash")

PKG = Path(__file__).resolve().parents[2] / "packaging" / "fnos"


class FnOS:
    """A fake fnOS install: TRIM_* directories plus a way to run the scripts."""

    def __init__(self, root: Path) -> None:
        self.appdest = root / "appdest"
        self.var = root / "var"
        self.etc = root / "etc"
        self.share = root / "share"
        self.temp_log = root / "temp.log"
        (self.appdest / "lib").mkdir(parents=True)
        shutil.copy(PKG / "app" / "lib" / "env.sh", self.appdest / "lib" / "env.sh")
        for d in (self.var, self.etc, self.share):  # data-share already exists on device
            d.mkdir()
        self.temp_log.touch()
        # A clean environment: nothing inherited from PHOTOVAULT_* / TRIM_*.
        self.env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(root),
            "TRIM_APPDEST": str(self.appdest),
            "TRIM_PKGVAR": str(self.var),
            "TRIM_PKGETC": str(self.etc),
            "TRIM_DATA_SHARE_PATHS": str(self.share),
            "TRIM_TEMP_LOGFILE": str(self.temp_log),
            "TRIM_APPNAME": "photovault",
            "TRIM_APPVER": "1.6",
            "TRIM_OLD_APPVER": "1.5",
            "TRIM_USERNAME": "photovault",
            "TRIM_SERVICE_PORT": "8000",
            "PV_TEST_PYTHON": sys.executable,
        }

    @property
    def pointer(self) -> Path:
        return self.share / ".workdir"

    @property
    def previous(self) -> Path:
        return self.share / ".workdir.previous"

    @property
    def app_log(self) -> str:
        path = self.var / "app.log"
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def run(self, name: str, status: str | None) -> None:
        env = dict(self.env)
        if status is not None:
            env["TRIM_APP_STATUS"] = status
        result = subprocess.run(
            ["bash", str(PKG / "cmd" / name)],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        assert result.returncode == 0, (
            f"{name} exited {result.returncode}\nstdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}\ntemp log:\n{self.temp_log.read_text()}"
        )

    def install(self) -> None:
        self.run("install_init", "INSTALL")
        self.run("install_callback", "INSTALL")

    def uninstall(self) -> None:
        self.run("uninstall_init", "UNINSTALL")
        self.run("uninstall_callback", "UNINSTALL")

    def upgrade(self, uninstall_status: str | None = "UPGRADE") -> None:
        """The order seen in the production log; the uninstall phase is configurable."""
        self.run("upgrade_init", "UPGRADE")
        self.run("uninstall_init", uninstall_status)
        self.run("uninstall_callback", uninstall_status)
        self.run("install_init", "UPGRADE")
        self.run("install_callback", "UPGRADE")
        self.run("upgrade_callback", "UPGRADE")

    def provision(self, work_dir: Path) -> None:
        """Write the pointer the way the server's set_workdir does."""
        self.pointer.write_text(f"{work_dir}\n", encoding="utf-8")


def make_library(work_dir: Path, users: int = 1) -> Path:
    work_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(work_dir / "photovault.db")
    try:
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT)")
        conn.executemany(
            "INSERT INTO users (username) VALUES (?)", [(f"u{i}",) for i in range(users)]
        )
        conn.commit()
    finally:
        conn.close()
    return work_dir


@pytest.fixture
def fnos(tmp_path) -> FnOS:
    return FnOS(tmp_path)


def test_upgrade_keeps_pointer(fnos, tmp_path):
    fnos.install()
    work = make_library(tmp_path / "vol2" / "PhotoVault")
    fnos.provision(work)

    fnos.upgrade()

    assert fnos.pointer.read_text(encoding="utf-8").strip() == str(work)
    assert not fnos.previous.exists()
    assert f"Upgrade: keeping work dir pointer ({work})" in fnos.app_log
    assert "Retired work dir pointer" not in fnos.app_log


def test_upgrade_restores_when_uninstall_phase_does_not_report_upgrade(fnos, tmp_path):
    fnos.install()
    work = make_library(tmp_path / "vol2" / "PhotoVault")
    fnos.provision(work)

    fnos.upgrade(uninstall_status=None)

    assert fnos.pointer.read_text(encoding="utf-8").strip() == str(work)
    assert not fnos.previous.exists()
    assert "Retired work dir pointer" in fnos.app_log
    assert f"restored work dir pointer from .workdir.previous ({work})" in fnos.app_log


def test_uninstall_retires_pointer_and_reinstall_runs_wizard(fnos, tmp_path):
    fnos.install()
    work = make_library(tmp_path / "vol2" / "PhotoVault")
    fnos.provision(work)

    fnos.uninstall()

    assert not fnos.pointer.exists()
    assert fnos.previous.read_text(encoding="utf-8").strip() == str(work)
    assert (work / "photovault.db").is_file()

    fnos.install()

    # A fresh install never adopts the old library: the wizard runs, with the hint.
    assert not fnos.pointer.exists()
    assert fnos.previous.read_text(encoding="utf-8").strip() == str(work)


def test_upgrade_restores_existing_retired_state(fnos, tmp_path):
    """Users who already upgraded with the old scripts: only .workdir.previous is left."""
    fnos.install()
    work = make_library(tmp_path / "vol2" / "PhotoVault")
    fnos.previous.write_text(f"{work}\n", encoding="utf-8")

    fnos.upgrade()

    assert fnos.pointer.read_text(encoding="utf-8").strip() == str(work)
    assert not fnos.previous.exists()


@pytest.mark.parametrize(
    "case",
    ["dir_missing", "no_db", "no_users", "garbage_db", "relative_path", "empty_pointer"],
)
def test_upgrade_does_not_restore_unadoptable_previous(fnos, tmp_path, case):
    fnos.install()
    work = tmp_path / "vol2" / "PhotoVault"
    content = f"{work}\n"
    if case == "no_db":
        work.mkdir(parents=True)
    elif case == "no_users":
        make_library(work, users=0)
    elif case == "garbage_db":
        work.mkdir(parents=True)
        (work / "photovault.db").write_bytes(b"not a sqlite database" * 50)
    elif case == "relative_path":
        make_library(work)
        content = "vol2/PhotoVault\n"
    elif case == "empty_pointer":
        content = ""
    fnos.previous.write_text(content, encoding="utf-8")
    db_before = (work / "photovault.db").read_bytes() if (work / "photovault.db").exists() else None

    fnos.upgrade()

    assert not fnos.pointer.exists()
    assert fnos.previous.read_text(encoding="utf-8") == content
    assert "not restoring work dir pointer" in fnos.app_log
    # The read-only check never creates or changes a database.
    db_after = (work / "photovault.db").read_bytes() if (work / "photovault.db").exists() else None
    assert db_after == db_before


def test_upgrade_unprovisioned_is_noop(fnos):
    fnos.install()

    fnos.upgrade()

    assert not fnos.pointer.exists()
    assert not fnos.previous.exists()


def test_upgrade_keeps_secret(fnos, tmp_path):
    fnos.install()
    secret = fnos.etc / "jwt_secret"
    assert secret.is_file() and secret.read_text().strip()
    before = secret.read_text()

    fnos.upgrade()

    assert secret.read_text() == before
