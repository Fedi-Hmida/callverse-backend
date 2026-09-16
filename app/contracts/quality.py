"""
Contrat partagé — Quality Analyst.
TODO (Ines): QualityScore, CriterionScore.
"""
from pydantic import BaseModel


class CriterionScore(BaseModel):
    criterion: str
    score: int
    evidence: str | None = None


class QualityScore(BaseModel):
    conversation_id: str
    global_score: float
    scores: dict[str, int]
    explanation: str
    evidence: list[CriterionScore]
    flags: dict
    recommendations: list[str]
