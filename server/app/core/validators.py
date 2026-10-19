"""Shared validation utilities.

Centralizes input validation rules so all endpoints use identical logic.
"""

from __future__ import annotations

import re

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
