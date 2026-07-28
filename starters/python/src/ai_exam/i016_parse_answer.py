from __future__ import annotations

from collections.abc import Mapping
from typing import Any


def parse_answer(text: str, schema: Mapping[str, type]) -> dict[str, Any]:
    raise NotImplementedError("TODO")
