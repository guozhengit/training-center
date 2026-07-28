from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


class CitationScorer(Protocol):
    def score(self, sentence: str, chunk: CitationChunk) -> float: ...


@dataclass(frozen=True, slots=True)
class CitationChunk:
    id: str
    text: str

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


@dataclass(frozen=True, slots=True)
class CitationScore:
    chunk_id: str
    score: float

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


@dataclass(frozen=True, slots=True)
class CitationEvidence:
    sentence: str
    cited_ids: tuple[str, ...]
    missing_ids: tuple[str, ...]
    scores: tuple[CitationScore, ...]
    supported: bool

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


@dataclass(frozen=True, slots=True)
class CitationReport:
    ok: bool
    missing_citation_ids: tuple[str, ...]
    uncited_sentences: tuple[str, ...]
    unsupported_sentences: tuple[str, ...]
    evidence: tuple[CitationEvidence, ...]

    def __post_init__(self) -> None:
        raise NotImplementedError("TODO")


def check_citations(
    answer: str,
    chunks: Sequence[CitationChunk],
    scorer: CitationScorer,
    threshold: float = 0.7,
) -> CitationReport:
    raise NotImplementedError("TODO")
