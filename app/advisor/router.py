"""
POST /ai/advisor/respond
"""
from fastapi import APIRouter

from app.contracts.advisor import AdvisorResponse
from .graph import build_graph

router = APIRouter()
_graph = None


@router.post("/respond", response_model=AdvisorResponse)
def respond(payload: dict):
    global _graph
    if _graph is None:
        _graph = build_graph()
    raise NotImplementedError
