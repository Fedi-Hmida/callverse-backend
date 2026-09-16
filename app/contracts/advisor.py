"""
Contrat partagé — Customer Advisor.
À figer en accord entre Douaa et Ines avant de coder les agents.
Consommé par app/quality/sourcing_check.py.
"""
from pydantic import BaseModel


class SourceRef(BaseModel):
    doc_id: str
    chunk_id: str
    score: float


class ToolCall(BaseModel):
    tool_name: str
    arguments: dict
    result: dict


class AdvisorResponse(BaseModel):
    answer: str
    sources: list[SourceRef]
    tool_calls: list[ToolCall]
    confidence: float  # 0-1, produit par le nœud "evaluate"
