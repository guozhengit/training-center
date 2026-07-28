from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any


Clock = Callable[[], float]


@dataclass(frozen=True, slots=True)
class CacheHit:
    value: Any
    similarity: float

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


class SemanticCache:
    def __init__(self, clock: Clock) -> None:
        pass

    def put(
        self,
        *,
        tenant_id: str,
        query_vector: Sequence[float],
        filters: Mapping[str, Any],
        model: str,
        version: str,
        value: Any,
        ttl: float,
    ) -> None:
        raise NotImplementedError("TODO")

    def get(
        self,
        *,
        tenant_id: str,
        query_vector: Sequence[float],
        filters: Mapping[str, Any],
        model: str,
        version: str,
        threshold: float,
    ) -> CacheHit | None:
        raise NotImplementedError("TODO")
