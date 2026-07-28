from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any


class StreamProtocolError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class StreamEvent:
    sequence: int
    kind: str
    data: Any


@dataclass(frozen=True, slots=True)
class StreamResult:
    text: str
    finish_reason: str
    usage: Mapping[str, int]


def assemble_stream(events: Iterable[StreamEvent]) -> StreamResult:
    raise NotImplementedError("TODO")
