from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol

from ai_exam.i027_tool_registry import ToolRegistry, ToolResult


class AgentState(str, Enum):
    PLAN = "PLAN"
    TOOL = "TOOL"
    OBSERVE = "OBSERVE"
    ANSWER = "ANSWER"


@dataclass(frozen=True, slots=True)
class AgentDecision:
    next_state: AgentState
    call: object | None = None
    answer: str | None = None


@dataclass(frozen=True, slots=True)
class AgentResult:
    answer: str
    steps: int
    trace: tuple[AgentState, ...]
    tool_results: tuple[ToolResult, ...]


class AgentPolicy(Protocol):
    def decide(self, state: AgentState, payload: Any) -> AgentDecision: ...


class AgentProtocolError(RuntimeError):
    pass


class UnknownToolError(AgentProtocolError):
    pass


class StepLimitError(RuntimeError):
    pass


class AgentRunner:
    def __init__(self, policy: AgentPolicy, registry: ToolRegistry) -> None:
        pass

    def run(self, initial: str, max_steps: int) -> AgentResult:
        raise NotImplementedError("TODO")
