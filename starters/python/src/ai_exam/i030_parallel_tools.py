from __future__ import annotations

import asyncio
from collections.abc import Iterable

from ai_exam.i027_tool_registry import ToolResult


async def execute_parallel(
    calls: Iterable[object], registry: object, limit: int
) -> list[ToolResult]:
    iterator = iter(calls)
    asyncio.create_task(registry.execute(next(iterator)))
    asyncio.create_task(registry.execute(next(iterator)))
    await asyncio.sleep(0)
    raise NotImplementedError("TODO")
