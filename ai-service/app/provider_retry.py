"""Bounded provider retries with a shared cooldown; never retry banking tools."""
from __future__ import annotations

import asyncio
import math
import re
import threading
import time
import weakref
from email.utils import parsedate_to_datetime
from datetime import datetime, timezone

from groq import RateLimitError


class ProviderRateLimited(RuntimeError):
    def __init__(self, retry_after: float):
        super().__init__("The model provider is rate limited; retry later.")
        self.retry_after = max(1, math.ceil(retry_after))


_cooldowns: dict[str, float] = {}
_guard = threading.Lock()
_loop_locks = weakref.WeakKeyDictionary()


def retry_after_seconds(error: RateLimitError, fallback: float = 5.0) -> float:
    """Prefer provider headers. Never include its body (which may contain account details)."""
    headers = error.response.headers
    try:
        if headers.get("retry-after-ms"):
            value = float(headers["retry-after-ms"]) / 1000
        elif headers.get("retry-after"):
            raw = headers["retry-after"]
            try:
                value = float(raw)
            except ValueError:
                value = (parsedate_to_datetime(raw) - datetime.now(timezone.utc)).total_seconds()
        else:
            match = re.search(r"try again in\s+([0-9.]+)\s*(ms|s|m)", str(error), re.I)
            value = float(match[1]) * {"ms": .001, "s": 1, "m": 60}[match[2].lower()] if match else fallback
        return max(1.0, value) if math.isfinite(value) else fallback
    except (ValueError, TypeError, OverflowError):
        return fallback


async def invoke_with_retry(llm, messages, *, model: str, max_retries: int = 2, max_wait: float = 20):
    # One in-flight generation per model in this worker. The router's deadline
    # also bounds queueing; cancellation releases the lock. Keep locks per event
    # loop so separate test clients/workers never share an asyncio primitive.
    loop = asyncio.get_running_loop()
    with _guard:
        locks = _loop_locks.setdefault(loop, {})
        lock = locks.setdefault(model, asyncio.Lock())
    async with lock:
        return await _invoke_with_retry(llm, messages, model=model, max_retries=max_retries, max_wait=max_wait)


async def _invoke_with_retry(llm, messages, *, model: str, max_retries: int, max_wait: float):
    """The router's outer deadline also bounds cooldown waits and all attempts together."""
    for attempt in range(max_retries + 1):
        while True:
            with _guard:
                cooldown = _cooldowns.get(model, 0)
                wait = max(0.0, cooldown - time.monotonic())
            if wait > max_wait:
                raise ProviderRateLimited(wait)
            if wait:
                await asyncio.sleep(wait)
            with _guard:
                # A different request can extend the shared cooldown during our sleep.
                # Respect that extension before spending another provider attempt.
                if _cooldowns.get(model, 0) <= cooldown:
                    break
        try:
            return await llm.ainvoke(messages)
        except RateLimitError as error:
            delay = retry_after_seconds(error, fallback=min(20, 5 * 2 ** attempt))
            with _guard:
                _cooldowns[model] = max(_cooldowns.get(model, 0), time.monotonic() + delay)
            if attempt == max_retries or delay > max_wait:
                raise ProviderRateLimited(delay) from error
    raise AssertionError("Unreachable retry state")
