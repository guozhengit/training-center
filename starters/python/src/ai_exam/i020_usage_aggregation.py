from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal


@dataclass(frozen=True, slots=True)
class UsageRecord:
    tenant_id: str
    model: str
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True, slots=True)
class ModelPrice:
    input_per_million: Decimal
    output_per_million: Decimal


@dataclass(frozen=True, slots=True)
class UsageTotal:
    input_tokens: int
    output_tokens: int
    cost: Decimal


@dataclass(frozen=True, slots=True)
class UsageSummary:
    by_model: Mapping[str, UsageTotal]
    by_tenant: Mapping[str, UsageTotal]
    grand_total: UsageTotal


def aggregate_usage(
    records: Sequence[UsageRecord], price_table: Mapping[str, ModelPrice]
) -> UsageSummary:
    raise NotImplementedError("TODO")
