"""API de propositions Workforce, sans exécution d'opérations."""
from fastapi import APIRouter, HTTPException, Query
from app.contracts.workforce import SKILLS, Strategy, WorkforceState, WorkforceAction
from .policy import decide, model_path, PolicyUnavailable

router = APIRouter()

@router.post("/decide", response_model=WorkforceAction)
def workforce_decide(state: WorkforceState,
                     strategy: Strategy = Query(default="PPO"),
                     threshold_n: int = Query(default=10, ge=0)):
    try:
        return decide(state, strategy=strategy, threshold_n=threshold_n)
    except PolicyUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

@router.get("/capabilities")
def capabilities():
    return {"skills": list(SKILLS), "strategies": ["PPO", "THRESHOLD", "STATIC_FIFO"],
            "actions": [{"id": 0, "type": "NONE"}] + [
                {"id": i + 1, "type": "REASSIGN", "to_pool": s, "count": 1}
                for i, s in enumerate(SKILLS)],
            "avg_wait_unit": "seconds", "model_wait_unit": "minutes",
            "observation_size": 15, "model_file_present": model_path().is_file(),
            "requires_approval": True,
            "donor_rule": "first_available_in_skill_order",
            "advisor_return_policy": "home_skill_after_service"}
