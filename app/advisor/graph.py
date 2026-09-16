"""
Construction du StateGraph LangGraph — Customer Advisor (Agentic RAG).
"""
from langgraph.graph import StateGraph, END

from .state import AdvisorState
from . import nodes


def build_graph() -> StateGraph:
    g = StateGraph(AdvisorState)
    g.add_node("decide_strategy", nodes.decide_strategy)
    g.add_node("retrieve_kb", nodes.retrieve_kb)
    g.add_node("call_tool", nodes.call_tool)
    g.add_node("evaluate_sufficiency", nodes.evaluate_sufficiency)
    g.add_node("respond", nodes.respond)
    g.add_node("escalate", nodes.escalate)

    g.set_entry_point("decide_strategy")
    g.add_conditional_edges(
        "decide_strategy",
        lambda s: s["next_action"],
        {"rag": "retrieve_kb", "tool": "call_tool"},
    )
    g.add_edge("retrieve_kb", "evaluate_sufficiency")
    g.add_edge("call_tool", "evaluate_sufficiency")
    g.add_conditional_edges(
        "evaluate_sufficiency",
        lambda s: "respond" if s["confidence"] >= 0.6 else "escalate",
        {"respond": "respond", "escalate": "escalate"},
    )
    g.add_edge("respond", END)
    g.add_edge("escalate", END)
    return g.compile()
