"""
StateGraph LangGraph — Client Simulator.
TODO (Douaa): generer_message -> evaluer_reponse_recue -> decider_action,
boucle conditionnelle tant que status == "en_cours".
"""
from langgraph.graph import StateGraph, END

from .state import SimulatorState


def build_graph() -> StateGraph:
    raise NotImplementedError
