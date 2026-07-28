from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol, TypeVar


T = TypeVar("T")


class Completer(Protocol[T]):
    def complete(self, request: Any) -> T: ...


class RandomSource(Protocol):
    def random(self) -> float: ...


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    max_attempts: int
    base_delay: float
    max_delay: float
    jitter_ratio: float
    transient_exceptions: tuple[type[Exception], ...]

    def validate(self) -> None:
        raise NotImplementedError("TODO")


def complete_with_retry(
    client: Completer[T],
    request: Any,
    policy: RetryPolicy,
    sleeper: Callable[[float], None],
    rng: RandomSource,
) -> T:
    raise NotImplementedError("TODO")
