"""
Contrat partagé — Workforce Manager.
TODO (Ines): WorkforceState, WorkforceAction.
Doit correspondre à ce que peut produire app/simulator (files d'attente, etc.)
— à définir ensemble avant de coder l'environnement Gymnasium.
"""
from pydantic import BaseModel


class WorkforceState(BaseModel):
    queues: dict[str, int]
    avg_wait: dict[str, float]
    available: dict[str, int]
    sla_today: float
    hour: int
    trend: float


class WorkforceAction(BaseModel):
    type: str  # ex: "REASSIGN"
    from_pool: str | None = None
    to_pool: str | None = None
    count: int | None = None
    reason: str
    expected_gain: dict
