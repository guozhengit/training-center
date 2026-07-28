from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


class Tokenizer(Protocol):
    def encode(self, text: str) -> Sequence[object]: ...


@dataclass(frozen=True, slots=True)
class Message:
    role: str
    content: str
    tool_call_ids: tuple[str, ...] = ()
    tool_call_id: str | None = None


def trim_messages(
    messages: Sequence[Message], budget: int, tokenizer: Tokenizer
) -> list[Message]:
    raise NotImplementedError("TODO")
