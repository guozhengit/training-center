from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from numpy.typing import ArrayLike

from ai_exam.retrieval_types import Document, Hit


class FilteredVectorIndex:
    def __init__(
        self, documents: Sequence[Document], vectors: ArrayLike
    ) -> None:
        pass

    def search(
        self,
        query: ArrayLike,
        k: int,
        tenant_id: str,
        filters: Mapping[str, Any] | None = None,
    ) -> list[Hit]:
        raise NotImplementedError("TODO")
