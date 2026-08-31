"""Process runtime metadata.

Tracks when the server process started so the "关于服务端" page can show an
uptime. Kept in its own module (instead of a global in main.py) so importing it
never drags in the FastAPI app and so tests can reset it.

The module import time is recorded as a fallback: ``mark_started()`` is called
from the FastAPI lifespan, but tests build the app without running lifespan, and
an uptime that is slightly too large is far better than a crash or a zero.
"""

from __future__ import annotations

import time
from datetime import datetime, timezone

# Import time of this module ≈ process start, used until mark_started() runs.
_IMPORT_MONOTONIC = time.monotonic()
_IMPORT_WALL_CLOCK = time.time()

_started_monotonic: float | None = None
_started_wall_clock: float | None = None


def mark_started() -> None:
    """Record the moment the application finished starting up.

    Called once from the FastAPI lifespan startup phase.
    """
    global _started_monotonic, _started_wall_clock
    _started_monotonic = time.monotonic()
    _started_wall_clock = time.time()


def reset_started() -> None:
    """Forget a previously recorded start time (test helper)."""
    global _started_monotonic, _started_wall_clock
    _started_monotonic = None
    _started_wall_clock = None


def get_started_at() -> datetime:
    """Return the start time as a timezone-aware UTC datetime."""
    wall_clock = _started_wall_clock if _started_wall_clock is not None else _IMPORT_WALL_CLOCK
    return datetime.fromtimestamp(wall_clock, tz=timezone.utc)


def get_uptime_seconds() -> float:
    """Return seconds elapsed since startup.

    Uses a monotonic clock so system clock adjustments (NTP sync on a NAS that
    just booted, for instance) cannot produce a negative uptime.
    """
    started = _started_monotonic if _started_monotonic is not None else _IMPORT_MONOTONIC
    return max(0.0, time.monotonic() - started)
