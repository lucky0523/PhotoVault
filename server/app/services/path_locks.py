"""In-process, per-path asyncio locks.

``path_lock(final_path)`` serialises work on one target path inside a single
worker process (e.g. two ``complete_upload`` calls that would replace the same
file). It is only an optimisation: correctness across workers and the repair
script comes from the database CAS, fcntl locks and the version journal (see
``app.services.version_service``).

Entries are reference counted and removed as soon as nobody holds or waits
for them, so the dict never grows with the number of paths ever seen, and no
``asyncio.Lock`` outlives the event loop it was bound to (pytest uses a new
loop per test). Only use from the event-loop thread.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass


@dataclass
class _Entry:
    lock: asyncio.Lock
    refs: int = 0


_entries: dict[str, _Entry] = {}


@asynccontextmanager
async def path_lock(path: str) -> AsyncIterator[None]:
    """Hold the process-local lock for ``path`` (the caller's ``str(final_path)``)."""
    entry = _entries.get(path)
    if entry is None:
        entry = _entries[path] = _Entry(asyncio.Lock())
    # No await between fetching the entry and counting ourselves in, so the
    # single-threaded event loop cannot interleave a deletion here.
    entry.refs += 1
    try:
        async with entry.lock:  # cancelled while waiting: finally still runs
            yield
    finally:
        entry.refs -= 1
        if entry.refs == 0:
            del _entries[path]
