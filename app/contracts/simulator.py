"""
Contrat partagé — Client Simulator.
TODO (Douaa): SimulatorMessage, SimulatorState.
"""
from pydantic import BaseModel


class SimulatorState(BaseModel):
    patience: float
    satisfaction: float
    objective: str
    profile: str


class SimulatorMessage(BaseModel):
    content: str
    state: SimulatorState
