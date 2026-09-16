"""
Détection de non-sourçage — consomme directement AdvisorResponse.sources / tool_calls
produits par app/advisor (Douaa). Ne jamais réimplémenter ce schéma ici.
TODO (Ines).
"""
from app.contracts.advisor import AdvisorResponse


def detect_unsourced_claims(advisor_response: AdvisorResponse) -> list[str]:
    raise NotImplementedError
