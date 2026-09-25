"""
État partagé du graphe LangGraph du Customer Advisor.
"""
from __future__ import annotations

from typing import Any, TypedDict

from app.contracts.advisor import AdvisorRequest


class AdvisorGraphState(TypedDict, total=False):
    request: AdvisorRequest
    decision: dict[str, Any]
    """Dernière décision prise par le noeud 'decide' (action, tool_name, arguments, reply, confidence, intent)."""
    retrieved_documents: list[dict]
    """Chaque élément attendu au minimum avec kb_article_id et score."""
    tool_calls: list[dict]
    """Chaque élément attendu avec name/arguments/result/error, converti en ToolCall dans finalize."""
    iterations: int
    # champs de sortie finale, alignés sur app.contracts.advisor.AdvisorResponse
    reply: str
    intent: str
    confidence: float
    action_type: str
    action_payload: dict
