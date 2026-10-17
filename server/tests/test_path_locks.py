"""Tests for the in-process, reference-counted path locks."""

import asyncio

import pytest

from app.services import path_locks
from app.services.path_locks import path_lock


async def test_entry_removed_after_exit():
    async with path_lock("/a/b.jpg"):
        assert path_locks._entries["/a/b.jpg"].refs == 1
    assert path_locks._entries == {}


async def test_concurrent_holders_are_serialised():
    order: list[str] = []
    inside = asyncio.Event()
    release = asyncio.Event()

    async def first():
        async with path_lock("/p"):
            order.append("first-in")
            inside.set()
            await release.wait()
            order.append("first-out")

    async def second():
        await inside.wait()
        async with path_lock("/p"):
            order.append("second-in")

    t1 = asyncio.create_task(first())
    t2 = asyncio.create_task(second())
    await inside.wait()
    await asyncio.sleep(0.01)
    assert path_locks._entries["/p"].refs == 2
    assert order == ["first-in"]
    release.set()
    await asyncio.gather(t1, t2)
    assert order == ["first-in", "first-out", "second-in"]
    assert path_locks._entries == {}


async def test_cancelled_waiter_keeps_count_correct():
    inside = asyncio.Event()
    release = asyncio.Event()

    async def holder():
        async with path_lock("/p"):
            inside.set()
            await release.wait()

    async def waiter():
        async with path_lock("/p"):
            pytest.fail("cancelled waiter must not get the lock")

    t1 = asyncio.create_task(holder())
    await inside.wait()
    t2 = asyncio.create_task(waiter())
    await asyncio.sleep(0.01)
    assert path_locks._entries["/p"].refs == 2
    t2.cancel()
    with pytest.raises(asyncio.CancelledError):
        await t2
    assert path_locks._entries["/p"].refs == 1
    release.set()
    await t1
    assert path_locks._entries == {}


async def test_different_paths_do_not_block_each_other():
    async with path_lock("/x"):
        async with path_lock("/y"):
            assert set(path_locks._entries) == {"/x", "/y"}
    assert path_locks._entries == {}
