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
