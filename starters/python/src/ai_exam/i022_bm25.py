from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence

from ai_exam.retrieval_types import Document, Hit


def tokenize(text: str) -> tuple[str, ...]:
    raise NotImplementedError("TODO")


class Bm25Index:
    def __init__(
        self,
        documents: Sequence[Document],
        *,
        k1: float = 1.5,
        b: float = 0.75,
        tokenizer: Callable[[str], Iterable[str]] = tokenize,
    ) -> None:
        pass

    def search(self, query: str, k: int, tenant_id: str) -> list[Hit]:
        raise NotImplementedError("TODO")
