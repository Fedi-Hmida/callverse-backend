"""
POST /ai/simulator/next-message -> reponse du client simule.
Document 01_Contrats_API.
"""

import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor

from fastapi import APIRouter, HTTPException
from groq import RateLimitError

from app.config import settings
from app.contracts.simulator import SimulatorRequest, SimulatorMessage
from app.simulator.graph import build_graph
from app.provider_retry import ProviderRateLimited, retry_after_seconds

logger = logging.getLogger(__name__)

router = APIRouter()

_graph = build_graph()

# Pool dédié et borné : un appel abandonné à l'échéance continue dans son thread jusqu'à la fin
# de l'appel LLM. Dans le pool par défaut, partagé avec les embeddings de l'Advisor, quelques
# simulations lentes suffiraient à faire attendre — puis échouer — l'Advisor.
_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="simulator")


@router.post("/next-message", response_model=SimulatorMessage, responses={
    429: {"description": "Quota fournisseur atteint; respecter Retry-After"},
    503: {"description": "Service LLM Groq indisponible"},
    504: {"description": "Échéance de la requête dépassée (le backend aurait abandonné)"},
})
async def next_message(request: SimulatorRequest) -> SimulatorMessage:
    messages = [{"role": m.role.value, "content": m.content} for m in request.history]
    reply = request.last_advisor_reply
    if reply and reply.strip() and not (
        messages and messages[-1]["role"] == "advisor"
        and messages[-1]["content"].strip() == reply.strip()
    ):
        messages.append({"role": "advisor", "content": reply})
    internal_state = {
        "profile": request.state.profile.value,
        "patience": request.state.patience,
        "satisfaction": request.state.satisfaction,
        "objective": request.state.objective,
        "objective_met": request.state.objective_met,
        "wait_seconds": request.wait_seconds,
        "messages": messages,
        "status": "en_cours",
    }
    try:
        # Le graphe est synchrone (appels LLM bloquants) : il tourne dans un thread, borné par l'échéance.
        loop = asyncio.get_running_loop()
        result = await asyncio.wait_for(
            loop.run_in_executor(_EXECUTOR, _graph.invoke, internal_state),
            timeout=settings.ai_request_deadline_seconds,
        )
    except (RateLimitError, ProviderRateLimited) as exc:
        delay = exc.retry_after if isinstance(exc, ProviderRateLimited) else ProviderRateLimited(retry_after_seconds(exc)).retry_after
        logger.warning("Simulator provider quota reached; retry after %ss", delay)
        raise HTTPException(status_code=429, detail="Quota du fournisseur IA atteint. Réessayez après le délai indiqué.",
                            headers={"Retry-After": str(delay)}) from exc
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
            "objective_met": result.get("objective_met", False),
        },
    )
