from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


class Tokenizer(Protocol):
    def encode(self, text: str) -> Sequence[str]: ...

    def decode(self, tokens: Sequence[str]) -> str: ...


@dataclass(frozen=True, slots=True)
class ContextHit:
    id: str
    text: str
    score: float


@dataclass(frozen=True, slots=True)
class PackedContext:
    text: str
    citation_ids: tuple[str, ...]
    used_tokens: int
    truncated_ids: tuple[str, ...]
    skipped_ids: tuple[str, ...]


def pack_context(
    hits: Sequence[ContextHit], budget: int, tokenizer: Tokenizer
) -> PackedContext:
    raise NotImplementedError("TODO")
