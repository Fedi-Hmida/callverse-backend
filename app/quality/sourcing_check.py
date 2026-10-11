"""Contrôle structurel des preuves. L'analyse sémantique revient au juge."""
from app.contracts.advisor import AdvisorResponse
from app.contracts.quality import JudgeResult, QualityRequest

def missing_source_keys(request: QualityRequest) -> list[str]:
    if request.advisor_response is None:
        return []
    provided = {(d.doc_id, d.chunk_id) for d in request.source_documents}
    return sorted({f"{s.doc_id}/{s.chunk_id}" for s in request.advisor_response.sources
                   if (s.doc_id, s.chunk_id) not in provided})

def validate_references(request: QualityRequest, result: JudgeResult) -> None:
    for item in [*result.evidence, *result.claims]:
        if not 0 <= item.message_index < len(request.messages):
            raise ValueError("Index de preuve hors de la conversation.")
        excerpt = item.evidence if hasattr(item, "evidence") else item.excerpt
        if excerpt not in request.messages[item.message_index].content:
            raise ValueError("L'extrait ne figure pas dans le message cité.")
    available = {f"{d.doc_id}/{d.chunk_id}" for d in request.source_documents}
    for claim in result.claims:
        if request.messages[claim.message_index].sender != "ADVISOR":
            raise ValueError("Une affirmation doit provenir du conseiller.")
        if not set(claim.source_keys) <= available:
            raise ValueError("Le juge cite un document non fourni.")
        if claim.status == "SUPPORTED" and not claim.source_keys:
            tools = request.advisor_response.tool_calls if request.advisor_response else []
            if not any(t.tool_name in claim.justification and t.result for t in tools):
                raise ValueError("Une affirmation confirmée nécessite un document ou un outil cité.")

def detect_unsourced_claims(advisor_response: AdvisorResponse) -> list[str]:
    """Sans textes sources, aucune détection sémantique fiable n'est possible."""
    raise ValueError("Utiliser evaluate(QualityRequest) avec les textes sources ; les identifiants seuls ne suffisent pas.")
