"""
Construction du graphe LangGraph du Customer Advisor.
"""
from __future__ import annotations

import time

from langgraph.graph import END, StateGraph

from app.advisor.nodes import decide, execute_tool, finalize, route_after_decide
from app.advisor.state import AdvisorGraphState
from app.contracts.advisor import Action, AdvisorRequest, AdvisorResponse, Source, ToolCall

_builder = StateGraph(AdvisorGraphState)
_builder.add_node("decide", decide)
_builder.add_node("execute_tool", execute_tool)
_builder.add_node("finalize", finalize)

_builder.set_entry_point("decide")
_builder.add_conditional_edges(
    "decide", route_after_decide, {"execute_tool": "execute_tool", "finalize": "finalize"}
)
_builder.add_edge("execute_tool", "decide")
_builder.add_edge("finalize", END)

advisor_graph = _builder.compile()


async def run_advisor(request: AdvisorRequest) -> AdvisorResponse:
    start = time.perf_counter()

    initial_state: AdvisorGraphState = {"request": request, "iterations": 0}
    final_state = await advisor_graph.ainvoke(initial_state)

    latency_ms = int((time.perf_counter() - start) * 1000)

    return AdvisorResponse(
        reply=final_state["reply"],
        intent=final_state["intent"],
        confidence=final_state["confidence"],
        tool_calls=[ToolCall(**c) for c in final_state.get("tool_calls", [])],
        sources=[Source(**s) for s in final_state.get("retrieved_documents", [])],
        action=Action(type=final_state["action_type"], payload=final_state.get("action_payload", {})),
        latency_ms=latency_ms,
    )
