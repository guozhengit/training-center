from __future__ import annotations

from collections.abc import Sequence

from ai_exam.retrieval_types import Hit


def reciprocal_rank_fusion(
    rankings: Sequence[Sequence[Hit]],
    k: int,
    constant: float = 60,
) -> list[Hit]:
    raise NotImplementedError("TODO")
