from fastapi import APIRouter

from app.advisor.graph import run_advisor
from app.contracts.advisor import AdvisorRequest, AdvisorResponse

router = APIRouter()


@router.post("/respond", response_model=AdvisorResponse)
async def respond(request: AdvisorRequest) -> AdvisorResponse:
    return await run_advisor(request)
