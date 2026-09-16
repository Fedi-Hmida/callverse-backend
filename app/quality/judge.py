"""
LLM-as-judge — sortie JSON contrainte (schéma app.contracts.quality.QualityScore).
TODO (Ines).
"""
from app.contracts.advisor import AdvisorResponse
from app.contracts.quality import QualityScore


def evaluate(conversation: list[dict], advisor_response: AdvisorResponse) -> QualityScore:
    # 1. sourcing_check.detect_unsourced_claims(advisor_response)
    # 2. appel LLM avec la grille des 6 critères, sortie JSON contrainte
    # 3. parse -> QualityScore (validation Pydantic, pas de texte libre)
    raise NotImplementedError
