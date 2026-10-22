"""Shared validation utilities.

Centralizes input validation rules so all endpoints use identical logic.

The username rule (``USERNAME_PATTERN``, ``RESERVED_USERNAMES``,
``BOOTSTRAP_ADMIN_USERNAME`` and both messages) is mirrored by the Web client in
``web/src/utils/validators.ts``; ``tests/test_username_rules_contract.py`` fails
when the two drift apart.
"""

from __future__ import annotations

import re

# ---------------------------------------------------------------------------
# Usernames
# ---------------------------------------------------------------------------
# The username is used verbatim as a path segment (``{media_root}/{username}``)
# next to internal directories such as ``.thumbnails`` and ``.versions``, so new
# accounts are restricted to a portable, lowercase ASCII form. Existing accounts
# are never re-validated: login stays an exact match on the stored name.

USERNAME_PATTERN = r"^[a-z0-9][a-z0-9_-]{1,30}[a-z0-9]$"
_USERNAME_RE = re.compile(USERNAME_PATTERN)

#: The first-run setup wizard may name the administrator ``admin`` (the only
#: exemption from ``RESERVED_USERNAMES``; see ``validate_username``).
BOOTSTRAP_ADMIN_USERNAME = "admin"

RESERVED_USERNAMES: frozenset[str] = frozenset(
    # Required by product decision.
    {"admin", "root", "system", "api", "static"}
    # First path segment of the API routes under /api/v1 (app/api/*.py, app/main.py).
    | {
        "setup",
        "auth",
        "connection",
        "backup",
        "files",
        "server",
        "explore",
        "stats",
        "clients",
        "fnos",
        "health",
    }
    # Static mounts in app/main.py.
    | {"assets"}
    # Top-level Web SPA routes (web/src/router/index.ts).
    | {
        "photos",
        "timeline",
        "statistics",
        "trash",
        "settings",
        "devices",
        "login",
        "register",
        "fnos-auth-callback",
        "app-auth",
    }
    # Directories the server creates next to the user dirs when
    # media_root == storage_root (app/core/config.py).
    | {"logs", "client-releases"}
    # App name (``/connection/test``).
    | {"photovault"}
    # Windows device names (exact matches only; ``com10`` / ``console`` are fine).
    | {"con", "prn", "aux", "nul"}
    | {f"com{i}" for i in range(1, 10)}
    | {f"lpt{i}" for i in range(1, 10)}
)

USERNAME_RULE_MESSAGE = (
    "用户名需为 3-32 位，只能包含小写英文字母、数字、下划线（_）和连字符（-），"
    "并以字母或数字开头和结尾"
)
USERNAME_RESERVED_MESSAGE = "该用户名为系统保留名称，请换一个用户名"


def validate_username(username: str, *, bootstrap_admin: bool = False) -> str | None:
    """Validate a username for a NEW account.

    Args:
        username: The name exactly as submitted (never stripped or lowercased).
        bootstrap_admin: True only for the first-run setup wizard, which may use
            ``BOOTSTRAP_ADMIN_USERNAME``. Every other reserved name stays blocked.

    Returns:
        None if the username is valid, or the user-facing error message.
    """
    # fullmatch, not match: Python's ``$`` also matches before a trailing "\n".
    if not _USERNAME_RE.fullmatch(username):
        return USERNAME_RULE_MESSAGE
    if username.lower() in RESERVED_USERNAMES:
        if bootstrap_admin and username == BOOTSTRAP_ADMIN_USERNAME:
            return None
        return USERNAME_RESERVED_MESSAGE
    return None

# Allowed password characters: ASCII letters, digits, and common symbols
_ALLOWED_PASSWORD_CHARS = re.compile(
    r'^[A-Za-z0-9!@#$%^&*()_+\-=\[\]{}|;:\'",.<>/?`~\\]+$'
)

_PASSWORD_MIN_LENGTH = 8

_PASSWORD_CHARS_MESSAGE = (
    "密码只能包含英文字母、数字和符号 !@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\"
)


_VIRTUAL_DIR_MAX_LENGTH = 1024


def normalize_virtual_dir_path(raw: str) -> str:
    """Validate and normalise a virtual directory path (``''`` = whole library).

    Shared by ``/stats/media`` and the Web timeline folder filter. Strips
    leading / trailing ``/``; each endpoint maps the error to its own status.

    Raises:
        ValueError: with a Chinese user-facing message when the path is too
            long, contains NUL / backslash, or has an empty / '.' / '..' segment.
    """
    path = raw.strip("/")
    if len(path) > _VIRTUAL_DIR_MAX_LENGTH:
        raise ValueError("路径过长")
    if "\x00" in path or "\\" in path:
        raise ValueError("路径包含非法字符")
    if path and any(seg in ("", ".", "..") for seg in path.split("/")):
        raise ValueError("路径格式无效")
    return path


def validate_password(password: str) -> str | None:
    """Validate a password against project-wide rules.

    Returns:
        None if the password is valid, or an error message string if invalid.
    """
    if len(password) < _PASSWORD_MIN_LENGTH:
        return f"密码长度不能少于{_PASSWORD_MIN_LENGTH}位"
    if not _ALLOWED_PASSWORD_CHARS.match(password):
        return _PASSWORD_CHARS_MESSAGE
    return None
