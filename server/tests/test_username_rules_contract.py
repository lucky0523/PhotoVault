"""The Web client mirrors the server's username rule byte for byte.

Source of truth: ``app/core/validators.py``. Mirror:
``web/src/utils/validators.ts``. This test fails on any drift.
"""

import re
from pathlib import Path

import pytest

from app.core.validators import (
    BOOTSTRAP_ADMIN_USERNAME,
    RESERVED_USERNAMES,
    USERNAME_PATTERN,
    USERNAME_RESERVED_MESSAGE,
    USERNAME_RULE_MESSAGE,
)

WEB_VALIDATORS = Path(__file__).resolve().parents[2] / "web" / "src" / "utils" / "validators.ts"


@pytest.fixture(scope="module")
def src() -> str:
    if not WEB_VALIDATORS.is_file():
        pytest.skip(f"{WEB_VALIDATORS} not present")
    return WEB_VALIDATORS.read_text(encoding="utf-8")


def _string_const(src: str, name: str) -> str:
    match = re.search(rf"^export const {name} = '([^']*)'\s*$", src, re.M)
    assert match, f"{name} not found as a single-quoted literal"
    return match.group(1)


def test_pattern_is_identical(src):
    match = re.search(r"^export const USERNAME_PATTERN = /(.+)/\s*$", src, re.M)
    assert match, "USERNAME_PATTERN not found"
    assert match.group(1) == USERNAME_PATTERN


def test_reserved_list_is_identical(src):
    match = re.search(
        r"export const RESERVED_USERNAMES: ReadonlySet<string> = new Set\(\[(.*?)\]\)",
        src,
        re.S,
    )
    assert match, "RESERVED_USERNAMES not found"
    names = re.findall(r"'([^']+)'", match.group(1))
    assert len(names) == len(set(names)), "duplicate entries"
    assert set(names) == RESERVED_USERNAMES


def test_messages_and_bootstrap_name_are_identical(src):
    assert _string_const(src, "USERNAME_RULE_MESSAGE") == USERNAME_RULE_MESSAGE
    assert _string_const(src, "USERNAME_RESERVED_MESSAGE") == USERNAME_RESERVED_MESSAGE
    assert _string_const(src, "BOOTSTRAP_ADMIN_USERNAME") == BOOTSTRAP_ADMIN_USERNAME
