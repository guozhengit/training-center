from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Hit:
    id: str
    score: float


def stable_top_k(
    scores: Sequence[float], ids: Sequence[str], k: int
) -> list[Hit]:
    raise NotImplementedError("TODO")
