"""
POST /ai/simulator/next-message -> reponse du client simule.
Document 01_Contrats_API.
"""

from fastapi import APIRouter

from app.contracts.simulator import SimulatorRequest, SimulatorMessage
from app.simulator.graph import build_graph

router = APIRouter()

_graph = build_graph()


@router.post("/next-message", response_model=SimulatorMessage)
def next_message(request: SimulatorRequest) -> SimulatorMessage:
    internal_state = {
        "profile": request.state.profile.value,
        "patience": request.state.patience,
        "satisfaction": request.state.satisfaction,
        "objective": request.state.objective,
        "messages": [
            {"role": m.role.value, "content": m.content} for m in request.history
        ],
        "status": "en_cours",
    }

    result = _graph.invoke(internal_state)

    last_message = result["messages"][-1]
    content = last_message["content"] if last_message["role"] == "customer" else None

    return SimulatorMessage(
        content=content,
        status=result["status"],
        state={
            "profile": result["profile"],
            "patience": result["patience"],
            "satisfaction": result["satisfaction"],
            "objective": result["objective"],
            "objective_met": result["status"] == "resolu",
        },
    )
