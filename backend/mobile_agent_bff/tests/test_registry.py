import asyncio

import pytest

from app.registry import DuplicateRequestError, RequestLimitError, RequestRegistry


@pytest.mark.asyncio
async def test_registry_enforces_owner_duplicate_and_concurrency() -> None:
    registry = RequestRegistry(max_per_subject=1)
    cancel = await registry.register("user-a", "request-1")

    with pytest.raises(DuplicateRequestError):
        await registry.register("user-a", "request-1")
    with pytest.raises(RequestLimitError):
        await registry.register("user-a", "request-2")
    assert await registry.cancel("user-b", "request-1") is False
    assert await registry.cancel("user-a", "request-1") is True
    assert cancel.is_set()

    await registry.unregister("user-a", "request-1")
    assert await registry.active_count() == 0


@pytest.mark.asyncio
async def test_cancel_immediately_cancels_attached_upstream_task() -> None:
    registry = RequestRegistry(max_per_subject=1)
    await registry.register("user-a", "request-1")
    started = asyncio.Event()

    async def upstream() -> object:
        started.set()
        await asyncio.sleep(60)
        return object()

    task = asyncio.create_task(upstream())
    await registry.attach_upstream_task("user-a", "request-1", task)
    await started.wait()

    assert await registry.cancel("user-a", "request-1") is True
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(task, timeout=0.2)

    await registry.unregister("user-a", "request-1")
