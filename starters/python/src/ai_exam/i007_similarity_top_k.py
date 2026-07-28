from __future__ import annotations

from collections.abc import Sequence

from numpy.typing import ArrayLike

from ai_exam.i009_stable_top_k import Hit


def top_k(
    query: ArrayLike, matrix: ArrayLike, ids: Sequence[str], k: int
) -> list[Hit]:
    raise NotImplementedError("TODO")
