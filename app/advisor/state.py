"""
État du graphe LangGraph — Customer Advisor.
"""
from typing import TypedDict
from langchain_core.messages import BaseMessage


class AdvisorState(TypedDict):
    messages: list[BaseMessage]
    customer_id: str
    retrieved_docs: list[dict]
    tool_calls: list[dict]
    confidence: float
    next_action: str  # "rag" | "tool" | "respond" | "escalate"
