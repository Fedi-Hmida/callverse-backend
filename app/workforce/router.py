"""
POST /ai/workforce/decide
"""
from fastapi import APIRouter

from app.contracts.workforce import WorkforceState, WorkforceAction
from .policy import decide

router = APIRouter()


@router.post("/decide", response_model=WorkforceAction)
def workforce_decide(state: WorkforceState):
    return decide(state)
