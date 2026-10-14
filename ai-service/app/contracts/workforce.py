"""Contrat API du modèle Workforce actuel (quatre files, cinq actions)."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

SKILLS = ("ACCOUNT", "CARD", "CREDIT", "ADVISORY")
Skill = Literal["ACCOUNT", "CARD", "CREDIT", "ADVISORY"]
Strategy = Literal["PPO", "THRESHOLD", "STATIC_FIFO"]
Count = Annotated[int, Field(ge=0, strict=True)]
Seconds = Annotated[float, Field(ge=0, allow_inf_nan=False)]

class WorkforceState(BaseModel):
    model_config = ConfigDict(extra="forbid")
    queues: dict[Skill, Count]
    avg_wait: dict[Skill, Seconds]  # Secondes dans l'API, minutes dans le modèle.
    available: dict[Skill, Count]
    sla_today: float = Field(ge=0, le=1, allow_inf_nan=False)
    hour: int = Field(ge=0, le=23, strict=True)
    trend: float = Field(ge=0, allow_inf_nan=False)

    @model_validator(mode="after")
    def complete_skills(self):
        for name in ("queues", "avg_wait", "available"):
            if set(getattr(self, name)) != set(SKILLS):
                raise ValueError(f"{name} doit contenir exactement les quatre files {SKILLS}.")
        return self

class WorkforceAction(BaseModel):
    type: Literal["NONE", "REASSIGN"]
    from_pool: Skill | None = None
    to_pool: Skill | None = None
    count: int | None = Field(default=None, ge=1)
    reason: str
    expected_gain: dict[str, float] = Field(default_factory=dict)
    strategy: Strategy = "PPO"
    action_id: int = Field(default=0, ge=0, le=4)
    requires_approval: bool = True
