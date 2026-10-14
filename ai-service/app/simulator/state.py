"""
État du graphe LangGraph — Client Simulator.
"""
from typing import TypedDict


class SimulatorState(TypedDict):
    profile: str  # calme, impatient, insatisfait, exigeant, offre, churn
    patience: float
    satisfaction: float
    objective: str
    messages: list
    status: str  # en_cours, resolu, abandonne
