"""
Contrat partagé — Client Simulator (document 01_Contrats_API).
POST /ai/simulator/next-message -> réponse du client simulé.

Ceci est l'état EXPOSE A L'API (échangé avec le backend), à ne pas
confondre avec app.simulator.state.SimulatorState qui est l'état
INTERNE du graphe LangGraph (TypedDict, ne sort jamais du service AI).
"""

from enum import Enum
from uuid import UUID

from pydantic import BaseModel, Field

from app.contracts.advisor import HistoryMessage


class CustomerProfile(str, Enum):
    calme = "calme"
    impatient = "impatient"
    insatisfait = "insatisfait"
    exigeant = "exigeant"
    offre = "offre"
    churn = "churn"  # transaction suspecte / risque de depart, cf. document banque


class SimulatorState(BaseModel):
    """Etat du client simule, tel qu'echange sur le contrat API."""

    profile: CustomerProfile
    patience: float = Field(ge=0, le=100)
    satisfaction: float = Field(ge=0, le=100)
    objective: str
    # Independent of perceived satisfaction: operational completion requires the caller's
    # verified state; an informational answer may be assessed with exact quoted evidence.
    objective_met: bool = Field(
        default=False,
        strict=True,
        description="Objectif confirme par preuve verifiee (operation: appelant; information: reponse citee), jamais par satisfaction seule.",
    )


class SimulatorRequest(BaseModel):
    conversation_id: UUID
    history: list[HistoryMessage] = Field(default_factory=list)
    last_advisor_reply: str | None = Field(
        default=None, description="Derniere reponse, ajoutee si elle n'est pas deja a la fin de l'historique.")
    wait_seconds: int = Field(
        ge=0, default=0,
        description="Attente supplementaire depuis le tour precedent, en secondes (pas un cumul).")
    state: SimulatorState


class SimulatorMessage(BaseModel):
    """Reponse du Client Simulator pour un tour de conversation."""

    content: str | None = None
    status: str = Field(description="en_cours | resolu | abandonne")
    state: SimulatorState
