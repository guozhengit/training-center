from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator, Mapping
from dataclasses import dataclass
from typing import Generic, TypeVar


T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class Split(Generic[T]):
    groups: Mapping[str, tuple[T, ...]]

    def __getitem__(self, name: str) -> tuple[T, ...]:
        raise NotImplementedError("TODO")

    def items(self) -> Iterator[tuple[str, tuple[T, ...]]]:
        raise NotImplementedError("TODO")


def split_dataset(
    items: Iterable[T],
    ratios: Mapping[str, float],
    seed: int,
    key: Callable[[T], str],
) -> Split[T]:
    raise NotImplementedError("TODO")
