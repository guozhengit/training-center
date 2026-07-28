from __future__ import annotations

from collections.abc import Sequence

from ai_exam.i011_fixed_chunks import Chunk


def recursive_chunks(
    text: str, size: int, separators: Sequence[str]
) -> list[Chunk]:
    raise NotImplementedError("TODO")
