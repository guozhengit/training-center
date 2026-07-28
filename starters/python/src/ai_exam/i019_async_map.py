from __future__ import annotations

from collections.abc import Awaitable, Callable, Iterable
from typing import TypeVar


T = TypeVar("T")
R = TypeVar("R")


async def map_limited(
    limit: int, inputs: Iterable[T], worker: Callable[[T], Awaitable[R]]
) -> list[R]:
    raise NotImplementedError("TODO")
