from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

from ai_exam.retrieval_types import Document, Hit


class BatchScorer(Protocol):
    def score(
        self, query: str, documents: Sequence[Document]
    ) -> Sequence[float]: ...


def rerank(
    query: str,
    hits: Sequence[Hit],
    scorer: BatchScorer,
    top_n: int,
) -> list[Hit]:
    raise NotImplementedError("TODO")
