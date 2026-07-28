from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import Any


def missing_stats(
    rows: Iterable[Mapping[str, Any]], fields: Sequence[str]
) -> dict[str, float]:
    raise NotImplementedError("TODO")
