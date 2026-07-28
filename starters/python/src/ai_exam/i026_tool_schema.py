from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class ValidatedCall:
    name: str
    arguments: Mapping[str, Any]


def validate_tool_schema(schema: object) -> Mapping[str, Any]:
    raise NotImplementedError("TODO")


def validate_tool_call(call: object, schema: object) -> ValidatedCall:
    raise NotImplementedError("TODO")
