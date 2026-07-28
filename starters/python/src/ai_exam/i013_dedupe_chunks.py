from __future__ import annotations

from collections.abc import Sequence

from ai_exam.i011_fixed_chunks import Chunk


def dedupe_chunks(chunks: Sequence[Chunk], threshold: float) -> list[Chunk]:
    raise NotImplementedError("TODO")
