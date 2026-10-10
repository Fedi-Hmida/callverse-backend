"""Contrats du Quality Analyst bancaire, notes sur 10."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from app.contracts.advisor import AdvisorResponse

CriterionCode = Literal["relevance", "accuracy", "compliance", "communication", "empathy", "resolution"]
CODES = {"relevance", "accuracy", "compliance", "communication", "empathy", "resolution"}

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

class Message(StrictModel):
    sender: Literal["CUSTOMER", "ADVISOR"]
    content: str = Field(min_length=1)

class SourceDocument(StrictModel):
    doc_id: str = Field(min_length=1)
    chunk_id: str = Field(min_length=1)
    content: str = Field(min_length=1)

class QualityRequest(StrictModel):
    conversation_id: str = Field(min_length=1)
    messages: list[Message] = Field(min_length=2)
    source_documents: list[SourceDocument] = Field(default_factory=list)
    advisor_response: AdvisorResponse | None = None
    resolved: bool | None = None
    # Procédures fictives applicables, fournies par la banque simulée.
    applicable_rules: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def check_context(self):
        if not any(m.sender == "ADVISOR" for m in self.messages):
            raise ValueError("La conversation doit contenir un message ADVISOR.")
        keys = [(d.doc_id, d.chunk_id) for d in self.source_documents]
        if len(keys) != len(set(keys)):
            raise ValueError("Documents dupliqués.")
        if self.advisor_response:
            last = next(m for m in reversed(self.messages) if m.sender == "ADVISOR")
            if last.content != self.advisor_response.answer:
                raise ValueError("advisor_response.answer doit correspondre au dernier message ADVISOR.")
        return self

class CriterionScore(StrictModel):
    criterion: CriterionCode
    score: int = Field(ge=0, le=10, strict=True)
    message_index: int = Field(ge=0)
    evidence: str = Field(min_length=1)
    justification: str = Field(min_length=1)

class ClaimAssessment(StrictModel):
    message_index: int = Field(ge=0)
    excerpt: str = Field(min_length=1)
    status: Literal["SUPPORTED", "UNSUPPORTED", "UNVERIFIABLE"]
    source_keys: list[str]  # Format doc_id/chunk_id
    justification: str = Field(min_length=1)

class JudgeResult(StrictModel):
    scores: dict[CriterionCode, int]
    evidence: list[CriterionScore]
    claims: list[ClaimAssessment]
    procedure_violations: list[str]
    explanation: str = Field(min_length=1)
    recommendations: list[str]

    @model_validator(mode="after")
    def check_scores(self):
        if set(self.scores) != CODES:
            raise ValueError("Les six critères sont obligatoires.")
        if any(type(v) is not int or not 0 <= v <= 10 for v in self.scores.values()):
            raise ValueError("Chaque note doit être un entier de 0 à 10.")
        if len(self.evidence) != 6 or {e.criterion for e in self.evidence} != CODES:
            raise ValueError("Un extrait est obligatoire pour chacun des six critères.")
        if any(e.score != self.scores[e.criterion] for e in self.evidence):
            raise ValueError("Les notes des preuves doivent correspondre aux notes des critères.")
        return self

class QualityFlags(StrictModel):
    unsourced_claims: int = Field(ge=0)
    unverifiable_claims: int = Field(ge=0)
    procedure_violations: list[str]
    missing_source_keys: list[str]

class QualityScore(StrictModel):
    conversation_id: str
    global_score: float = Field(ge=0, le=10)
    scores: dict[CriterionCode, int]
    explanation: str
    evidence: list[CriterionScore]
    flags: QualityFlags
    recommendations: list[str]
    claims: list[ClaimAssessment]
