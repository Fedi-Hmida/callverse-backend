from fastapi import APIRouter, HTTPException

from app.advisor.graph import run_advisor
from app.contracts.advisor import AdvisorRequest, AdvisorResponse

router = APIRouter()


@router.post("/respond", response_model=AdvisorResponse, responses={
    503: {"description": "Service LLM Groq ou base pgvector indisponible"}
})
async def respond(request: AdvisorRequest) -> AdvisorResponse:
    try:
        return await run_advisor(request)
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=f"Service Advisor temporairement indisponible: {exc}"
        ) from exc

