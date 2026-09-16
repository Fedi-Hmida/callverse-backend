"""
POST /ai/simulator/next-message
"""
from fastapi import APIRouter

router = APIRouter()


@router.post("/next-message")
def next_message(payload: dict):
    raise NotImplementedError
