from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class LoopState:
    step: int
    signature: str
    tokens_used: int


@dataclass(frozen=True, slots=True)
class GuardDecision:
    allowed: bool
    reasons: tuple[str, ...]
    signature_count: int

    @property
    def should_stop(self) -> bool:
        raise NotImplementedError("TODO")


class LoopGuard:
    def __init__(
        self, *, max_steps: int, max_repeats: int, token_budget: int
    ) -> None:
        pass

    def observe(self, state: LoopState) -> GuardDecision:
        raise NotImplementedError("TODO")
