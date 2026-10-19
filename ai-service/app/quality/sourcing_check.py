"""Contrôle structurel des preuves. L'analyse sémantique revient au juge."""
from app.contracts.advisor import AdvisorResponse
import logging
import re
import unicodedata

from app.contracts.quality import JudgeResult, QualityRequest


def _normalize(text: str) -> str:
    """Ignore les différences de forme (apostrophes typographiques, espaces, casse)."""
    # Some JSON generators double-escape line breaks inside copied quotations.
    # Compare their whitespace form only; this never supplies missing words.
    text = text.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
    text = (text.replace("\u2019", "'").replace("\u2018", "'")
                .replace("\u00a0", " ").replace("\u202f", " "))
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = re.sub(r"\s+", " ", text).strip().casefold()
    # French typography allows a space before these punctuation marks. Its
    # omission must not turn an otherwise identical quote into fabricated text.
    return re.sub(r"\s+([!?;:])", r"\1", text)

def missing_source_keys(request: QualityRequest) -> list[str]:
    if request.advisor_response is None:
        return []
    # Une source de l'Advisor référence un article entier (kb_article_id) ;
    # elle est couverte si un document fourni porte ce doc_id, quel que soit le chunk.
    provided = {d.doc_id for d in request.source_documents}
    return sorted({str(s.kb_article_id) for s in request.advisor_response.sources
                   if str(s.kb_article_id) not in provided})

def align_reference_indices(request: QualityRequest, result: JudgeResult) -> None:
    """Repair a model's wrong index only when its quote uniquely identifies text.

    Never invent or rewrite a quote. Claims can only be aligned to advisor text;
    absent/ambiguous matches are left unchanged for strict validation to reject.
    """
    for item in [*result.evidence, *result.claims]:
        if not 0 <= item.message_index < len(request.messages):
            continue
        is_evidence = hasattr(item, "evidence")
        quote = _normalize(item.evidence if is_evidence else item.excerpt)
        if not quote:
            continue
        matches = [i for i, message in enumerate(request.messages)
                   if (is_evidence or message.sender == "ADVISOR")
                   and quote in _normalize(message.content)]
        if item.message_index not in matches and len(matches) == 1:
            item.message_index = matches[0]


def validate_references(request: QualityRequest, result: JudgeResult) -> None:
    for item in [*result.evidence, *result.claims]:
        if not 0 <= item.message_index < len(request.messages):
            raise ValueError("Index de preuve hors de la conversation.")
        excerpt = item.evidence if hasattr(item, "evidence") else item.excerpt
        normalized_excerpt = _normalize(excerpt)
        if not normalized_excerpt:
            raise ValueError("L'extrait doit contenir du texte vérifiable.")
        if normalized_excerpt not in _normalize(request.messages[item.message_index].content):
            logging.getLogger(__name__).warning(
                "Quality quote rejected: message_index=%s",
                item.message_index)
            raise ValueError("L'extrait ne figure pas dans le message cité.")
    available = {f"{d.doc_id}/{d.chunk_id}" for d in request.source_documents}
    for claim in result.claims:
        if request.messages[claim.message_index].sender != "ADVISOR":
            raise ValueError("Une affirmation doit provenir du conseiller.")
        if not set(claim.source_keys) <= available:
            raise ValueError("Le juge cite un document non fourni.")
        if claim.status == "SUPPORTED" and not claim.source_keys:
            tools = request.advisor_response.tool_calls if request.advisor_response else []
            if not any(t.ok and t.tool in claim.justification and t.result_summary.strip() for t in tools):
                raise ValueError("Une affirmation confirmée nécessite un document ou un outil cité.")

def detect_unsourced_claims(advisor_response: AdvisorResponse) -> list[str]:
    """Sans textes sources, aucune détection sémantique fiable n'est possible."""
    raise ValueError("Utiliser evaluate(QualityRequest) avec les textes sources ; les identifiants seuls ne suffisent pas.")
