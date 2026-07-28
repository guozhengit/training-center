from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Literal


ToolStatus = Literal[
    "ok",
    "invalid_call",
    "unknown_tool",
    "timeout",
    "handler_error",
    "configuration_error",
]


@dataclass(frozen=True, slots=True)
class ToolResult:
    name: str
    status: ToolStatus
    output: Any = None
    error: str | None = None

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")

    @property
    def ok(self) -> bool:
        raise NotImplementedError("TODO")


def validate_tool_result(result: object) -> ToolResult:
    raise NotImplementedError("TODO")


class ToolRegistry:
    def __init__(
        self,
        *,
        timeout_runner: Callable[
            [Callable[[Mapping[str, Any]], Any], Mapping[str, Any], float], Any
        ]
        | None = None,
    ) -> None:
        pass

    def register(
        self,
        name: str,
        schema: object,
        handler: Callable[[Mapping[str, Any]], Any],
        *,
        timeout: float | None = None,
    ) -> None:
        raise NotImplementedError("TODO")

    def execute(self, call: object) -> ToolResult:
        raise NotImplementedError("TODO")
