"""POST /ai/quality/evaluate : entrée validée, erreurs du juge explicites."""
from fastapi import APIRouter, HTTPException
from app.contracts.quality import QualityRequest, QualityScore
from .judge import evaluate, InvalidJudgeOutput, JudgeUnavailable, JudgeTimeout

router = APIRouter()

@router.post("/evaluate", response_model=QualityScore, responses={
    502: {"description": "Sortie du juge invalide"},
    503: {"description": "Ollama non configure, inaccessible ou en erreur"},
    504: {"description": "Delai de generation Ollama depasse"},
})
def quality_evaluate(payload: QualityRequest):
    try:
        return evaluate(payload)
    except JudgeTimeout as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except JudgeUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except InvalidJudgeOutput as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@router.get("/rubric")
def quality_rubric():
    from dataclasses import asdict
    from .rubric import RUBRIC, SCORE_MIN, SCORE_MAX
    return {"version": "banking-v1", "score_min": SCORE_MIN, "score_max": SCORE_MAX,
            "criteria": [asdict(c) for c in RUBRIC]}
