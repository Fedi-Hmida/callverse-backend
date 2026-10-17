"""
POST /ai/simulator/next-message -> reponse du client simule.
Document 01_Contrats_API.
"""

import asyncio
import logging

from fastapi import APIRouter, HTTPException

from app.config import settings
from app.contracts.simulator import SimulatorRequest, SimulatorMessage
from app.simulator.graph import build_graph

logger = logging.getLogger(__name__)

router = APIRouter()

_graph = build_graph()


@router.post("/next-message", response_model=SimulatorMessage, responses={
    503: {"description": "Service LLM Groq indisponible"},
    504: {"description": "Échéance de la requête dépassée (le backend aurait abandonné)"},
})
async def next_message(request: SimulatorRequest) -> SimulatorMessage:
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
    try:
        # Le graphe est synchrone (appels LLM bloquants) : il tourne dans un thread, borné par l'échéance.
        result = await asyncio.wait_for(
            asyncio.to_thread(_graph.invoke, internal_state), timeout=settings.ai_request_deadline_seconds
        )
    except asyncio.TimeoutError as exc:
        logger.warning("Simulator deadline exceeded: %ss", settings.ai_request_deadline_seconds)
        raise HTTPException(status_code=504, detail="Échéance du Simulator dépassée") from exc
    except Exception as exc:
        logger.exception("Simulator failed")
        raise HTTPException(status_code=503, detail="Service Simulator temporairement indisponible") from exc

    # Contrat : un statut final n'a pas de message ; en cours, le dernier message du client.
    content = None
    if result["status"] == "en_cours" and result["messages"] and result["messages"][-1]["role"] == "customer":
        content = result["messages"][-1]["content"]

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
