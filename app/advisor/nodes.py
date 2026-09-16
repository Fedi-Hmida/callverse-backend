"""
Fonctions des nœuds du graphe : decide_strategy, retrieve_kb, call_tool,
evaluate_sufficiency, respond, escalate.
Chaque fonction prend un AdvisorState et retourne un dict partiel (merge).
TODO (Douaa): implémenter la logique de chaque nœud.
"""
from .state import AdvisorState


def decide_strategy(state: AdvisorState) -> dict:
    raise NotImplementedError


def retrieve_kb(state: AdvisorState) -> dict:
    raise NotImplementedError


def call_tool(state: AdvisorState) -> dict:
    raise NotImplementedError


def evaluate_sufficiency(state: AdvisorState) -> dict:
    raise NotImplementedError


def respond(state: AdvisorState) -> dict:
    raise NotImplementedError


def escalate(state: AdvisorState) -> dict:
    raise NotImplementedError
