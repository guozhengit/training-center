from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence


@dataclass(frozen=True, slots=True)
class Reject:
    line_number: int
    reason: str
    raw: str


def clean_jsonl(
    lines: Iterable[str], required: Sequence[str]
) -> tuple[list[dict], list[Reject]]:
    raise NotImplementedError("TODO")
