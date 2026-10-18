import asyncio
import logging

from fastapi import APIRouter, HTTPException

from app.advisor.graph import run_advisor
from app.config import settings
from app.contracts.advisor import AdvisorRequest, AdvisorResponse
from app.provider_retry import ProviderRateLimited

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/respond", response_model=AdvisorResponse, responses={
    429: {"description": "Quota fournisseur atteint; respecter Retry-After"},
    503: {"description": "Service LLM Groq ou base pgvector indisponible"},
    504: {"description": "Échéance de la requête dépassée (le backend aurait abandonné)"},
})
async def respond(request: AdvisorRequest) -> AdvisorResponse:
    try:
        return await asyncio.wait_for(run_advisor(request), timeout=settings.ai_request_deadline_seconds)
    except ProviderRateLimited as exc:
        logger.warning("Advisor provider quota reached; retry after %ss", exc.retry_after)
        raise HTTPException(status_code=429, detail="Quota du fournisseur IA atteint. Réessayez après le délai indiqué.",
                            headers={"Retry-After": str(exc.retry_after)}) from exc
    except asyncio.TimeoutError as exc:
        logger.warning("Advisor deadline exceeded: %ss", settings.ai_request_deadline_seconds)
        raise HTTPException(status_code=504, detail="Échéance de l'Advisor dépassée") from exc
    except Exception as exc:
        # Le détail reste dans les journaux : une erreur de connexion peut contenir un DSN.
        logger.exception("Advisor failed")
        raise HTTPException(status_code=503, detail="Service Advisor temporairement indisponible") from exc
