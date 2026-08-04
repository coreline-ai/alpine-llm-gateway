from __future__ import annotations

import asyncio
from collections import Counter
from dataclasses import dataclass


class DuplicateRequestError(Exception):
    pass


class RequestLimitError(Exception):
    pass


@dataclass
class _ActiveRequest:
    cancel: asyncio.Event
    upstream_task: asyncio.Task[object] | None = None


class RequestRegistry:
    def __init__(self, max_per_subject: int):
        self._max_per_subject = max_per_subject
        self._requests: dict[tuple[str, str], _ActiveRequest] = {}
        self._lock = asyncio.Lock()

    async def register(self, subject: str, request_id: str) -> asyncio.Event:
        key = (subject, request_id)
        async with self._lock:
            if key in self._requests:
                raise DuplicateRequestError
            active = Counter(owner for owner, _ in self._requests)[subject]
            if active >= self._max_per_subject:
                raise RequestLimitError
            event = asyncio.Event()
            self._requests[key] = _ActiveRequest(cancel=event)
            return event

    async def attach_upstream_task(
        self,
        subject: str,
        request_id: str,
        task: asyncio.Task[object],
    ) -> None:
        async with self._lock:
            active = self._requests.get((subject, request_id))
            if active is None:
                task.cancel()
                return
            active.upstream_task = task
            if active.cancel.is_set():
                task.cancel()

    async def cancel(self, subject: str, request_id: str) -> bool:
        async with self._lock:
            active = self._requests.get((subject, request_id))
            if active is None:
                return False
            active.cancel.set()
            if active.upstream_task is not None:
                active.upstream_task.cancel()
            return True

    async def unregister(self, subject: str, request_id: str) -> None:
        async with self._lock:
            self._requests.pop((subject, request_id), None)

    async def active_count(self) -> int:
        async with self._lock:
            return len(self._requests)
