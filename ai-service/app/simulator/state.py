"""
État du graphe LangGraph — Client Simulator.
"""
from typing import NotRequired, TypedDict


class SimulatorState(TypedDict):
    profile: str  # calme, impatient, insatisfait, exigeant, offre, churn
    patience: float
    satisfaction: float
    objective: str
    objective_met: NotRequired[bool]  # Completion confirmed by the caller, not inferred from prose.
    wait_seconds: NotRequired[int]  # Additional waiting time since the previous turn.
    messages: list
    status: str  # en_cours, resolu, abandonne
