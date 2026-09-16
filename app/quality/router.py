"""
POST /ai/quality/evaluate
"""
from fastapi import APIRouter

from app.contracts.quality import QualityScore

router = APIRouter()


@router.post("/evaluate", response_model=QualityScore)
def evaluate(payload: dict):
    raise NotImplementedError
