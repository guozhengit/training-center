from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Chunk:
    text: str
    start: int
    end: int


def fixed_chunks(text: str, size: int, overlap: int) -> list[Chunk]:
    raise NotImplementedError("TODO")
