"""
Contrat 1 — Backend vers Service AI (document 01_Contrats_API)
Version bancaire de CallVerse.
Champs non négociables : sources, tool_calls, confidence.
"""

from datetime import datetime
from enum import Enum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class Role(str, Enum):
    customer = "customer"
    advisor = "advisor"


class HistoryMessage(BaseModel):
    role: Role
    content: str
    ts: datetime


class ChurnRisk(str, Enum):
    low = "LOW"
    medium = "MEDIUM"
    high = "HIGH"


class AdvisorContext(BaseModel):
    contract_type: str | None = None
    tenure_months: int = Field(ge=0)
    churn_risk: ChurnRisk | None = None
    open_tickets: int | None = Field(default=None, ge=0)
    segment: str | None = None


class Mode(str, Enum):
    live = "LIVE"
    simulation = "SIMULATION"


class AdvisorRequest(BaseModel):
    conversation_id: UUID
    customer_id: UUID
    message: str
    history: list[HistoryMessage] = Field(default_factory=list)
    context: AdvisorContext
    mode: Mode


# --- Réponse ---

Intent = Literal["BALANCE", "CARD", "CREDIT", "FRAUD", "ACCOUNT_CLOSURE", "OTHER"]
ActionType = Literal["NONE", "CREATE_CASE", "APPLY_CREDIT", "ESCALATE", "TRANSFER", "BLOCK_CARD"]

BankingTool = Literal[
    "get_customer",
    "get_transactions",
    "check_system_status",
    "search_knowledge_base",
    "create_case",
    "apply_credit",
    "escalate",
]


class ToolCall(BaseModel):
    tool: BankingTool
    args: dict
    result_summary: str
    ok: bool


class Source(BaseModel):
    kb_article_id: UUID
    score: float = Field(ge=0.0, le=1.0)


class Action(BaseModel):
    type: ActionType
    payload: dict = Field(default_factory=dict)


class AdvisorResponse(BaseModel):
    reply: str
    intent: Intent
    confidence: float = Field(ge=0.0, le=1.0)
    tool_calls: list[ToolCall] = Field(default_factory=list)
    sources: list[Source] = Field(default_factory=list)
    action: Action
    latency_ms: int = Field(ge=0)
