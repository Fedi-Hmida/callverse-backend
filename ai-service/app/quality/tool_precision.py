"""
Metrique : precision d'appel d'outils (document 05 — CallVerse).

Compare, pour un ensemble de cas de test, l'outil metier attendu face
a un message client donne, avec les outils reellement appeles par
l'Advisor (response.tool_calls).

Deux mesures :
- rappel : l'outil attendu a-t-il ete appele au moins une fois ?
- precision : parmi les outils appeles, quelle proportion etait pertinente
  (c'est-a-dire figurait dans les outils attendus pour ce cas) ?
"""
from __future__ import annotations

from dataclasses import dataclass, field

from app.contracts.advisor import AdvisorResponse


@dataclass
class ToolCase:
    """Un cas de test : message client + outils qu'on attend de voir appeles."""

    name: str
    message: str
    expected_tools: set[str]
    # outils tolerables sans compter comme une erreur de precision
    # (ex: get_customer avant une action sensible est souvent legitime
    # meme si on n'attendait que search_knowledge_base)
    acceptable_tools: set[str] = field(default_factory=set)


@dataclass
class ToolCaseResult:
    case_name: str
    expected_tools: set[str]
    called_tools: set[str]
    recall_hit: bool
    precision: float


def evaluate_case(case: ToolCase, response: AdvisorResponse) -> ToolCaseResult:
    called = {tc.tool for tc in response.tool_calls}

    recall_hit = bool(called & case.expected_tools)

    allowed = case.expected_tools | case.acceptable_tools
    relevant_calls = called & allowed
    precision = len(relevant_calls) / len(called) if called else 0.0

    return ToolCaseResult(
        case_name=case.name,
        expected_tools=case.expected_tools,
        called_tools=called,
        recall_hit=recall_hit,
        precision=precision,
    )


def summarize(results: list[ToolCaseResult]) -> dict:
    n = len(results)
    if n == 0:
        return {"n_cases": 0, "recall": 0.0, "avg_precision": 0.0}

    recall = sum(1 for r in results if r.recall_hit) / n
    avg_precision = sum(r.precision for r in results) / n

    return {
        "n_cases": n,
        "recall": recall,
        "avg_precision": avg_precision,
    }