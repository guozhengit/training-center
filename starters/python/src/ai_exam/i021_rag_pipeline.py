from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Protocol

from ai_exam.i014_pack_context import Tokenizer
from ai_exam.retrieval_types import Hit, RagAnswer
from ai_exam.support import ModelClient


class Retriever(Protocol):
    def search(self, query: str, k: int, tenant_id: str) -> Sequence[Hit]: ...


PromptBuilder = Callable[[str, str], str]


def _default_prompt(question: str, context: str) -> str:
    raise NotImplementedError("TODO")


class RagPipeline:
    def __init__(
        self,
        retriever: Retriever,
        tokenizer: Tokenizer,
        model: ModelClient,
        *,
        k: int = 5,
        context_budget: int = 2_000,
        no_hits_text: str = "未检索到可用依据。",
        prompt_builder: PromptBuilder = _default_prompt,
    ) -> None:
        pass

    def answer(self, question: str, tenant_id: str) -> RagAnswer:
        raise NotImplementedError("TODO")
