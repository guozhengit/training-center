from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Protocol


class Retriever(Protocol):
    def retrieve(self, query: str, k: int) -> Iterable[str]: ...


@dataclass(frozen=True, slots=True)
class RetrievalCase:
    query: str
    relevant_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


@dataclass(frozen=True, slots=True)
class RetrievalMetrics:
    recall_at_k: Mapping[int, float]
    hit_rate_at_k: Mapping[int, float]
    mrr: float
    mrr_cutoff: int

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


def evaluate_retrieval(
    cases: Iterable[RetrievalCase], retriever: Retriever, ks: Iterable[int]
) -> RetrievalMetrics:
    raise NotImplementedError("TODO")
