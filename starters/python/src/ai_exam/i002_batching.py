from __future__ import annotations

from collections.abc import Iterable, Iterator
from typing import TypeVar


T = TypeVar("T")


def batched(items: Iterable[T], size: int) -> Iterator[tuple[T, ...]]:
    raise NotImplementedError("TODO")
