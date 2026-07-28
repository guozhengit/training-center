from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class SafetyChunk:
    source_id: str
    text: str


@dataclass(frozen=True, slots=True)
class PolicyFinding:
    code: str
    detail: str

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


@dataclass(frozen=True, slots=True)
class SafetyDecision:
    allowed: bool
    reason_codes: tuple[str, ...]
    details: tuple[str, ...]
    prompt: str | None

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


class InspectionPolicy(Protocol):
    def inspect(self, text: str, source: str) -> Iterable[PolicyFinding]: ...

    def allows(self, reason_codes: tuple[str, ...]) -> bool: ...


@dataclass(frozen=True, slots=True)
class DeterministicPolicy:
    def inspect(self, text: str, source: str) -> tuple[PolicyFinding, ...]:
        raise NotImplementedError("TODO")

    def allows(self, reason_codes: tuple[str, ...]) -> bool:
        raise NotImplementedError("TODO")


def inspect_and_build(
    query: str,
    chunks: Sequence[SafetyChunk],
    policy: InspectionPolicy,
) -> SafetyDecision:
    raise NotImplementedError("TODO")
