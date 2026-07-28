from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from numpy.typing import ArrayLike


@dataclass(frozen=True, slots=True)
class VectorHit:
    id: str
    score: float
    metadata: Mapping[str, Any]


class InMemoryVectorStore:
    def __init__(self) -> None:
        pass

    def upsert(
        self,
        tenant_id: str,
        document_id: str,
        vector: ArrayLike,
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError("TODO")

    def delete(self, tenant_id: str, document_id: str) -> bool:
        raise NotImplementedError("TODO")

    def search(
        self,
        tenant_id: str,
        query: ArrayLike,
        k: int,
        filters: Mapping[str, Any] | None = None,
    ) -> list[VectorHit]:
        raise NotImplementedError("TODO")
