"""Juge Ollama et assemblage déterministe du résultat qualité."""
import json
import logging
import re
import httpx
from pydantic import ValidationError
from app.config import settings
from app.contracts.quality import JudgeResult, QualityFlags, QualityRequest, QualityScore
from .prompts import SYSTEM_PROMPT
from .rubric import RUBRIC, compute_global_score, validate_rubric
from .sourcing_check import missing_source_keys, validate_references

class JudgeUnavailable(RuntimeError):
    pass

class JudgeTimeout(JudgeUnavailable):
    pass

logger = logging.getLogger(__name__)

class InvalidJudgeOutput(RuntimeError):
    pass

def call_judge(request: QualityRequest) -> JudgeResult:
    if not settings.quality_model:
        raise JudgeUnavailable("Configurer QUALITY_MODEL avec un modèle installé dans Ollama.")
    context = request.model_dump(mode="json")
    context["rubric"] = [vars(c) for c in RUBRIC]
    context["output_schema"] = JudgeResult.model_json_schema()
    context["available_source_keys"] = [
        f"{d.doc_id}/{d.chunk_id}" for d in request.source_documents]
    context["citation_candidates"] = [
        {"message_index": i, "sender": message.sender,
         "excerpts": [message.content] + [
             part for part in re.split(r"(?<=[.!?])\s+", message.content)
             if part != message.content]}
        for i, message in enumerate(request.messages)]
    try:
        with httpx.Client(timeout=httpx.Timeout(settings.quality_timeout_seconds, connect=10.0)) as client:
            response = client.post(
                settings.quality_ollama_url.rstrip("/") + "/api/chat",
                json={"model": settings.quality_model, "stream": False,
                      "format": JudgeResult.model_json_schema(),
                      "options": {"temperature": 0},
                      "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                                   {"role": "user", "content": json.dumps(context, ensure_ascii=False)}]},
            )
            response.raise_for_status()
    except httpx.ConnectTimeout as exc:
        raise JudgeUnavailable("Connexion a Ollama trop lente. Verifier QUALITY_OLLAMA_URL et le service Ollama.") from exc
    except httpx.TimeoutException as exc:
        logger.warning("Ollama timeout: model=%s read_timeout=%s",
                       settings.quality_model, settings.quality_timeout_seconds)
        raise JudgeTimeout(
            f"Ollama n'a pas termine l'evaluation dans le delai de "
            f"{settings.quality_timeout_seconds:g} secondes. "
            "Le modele peut etre lent sur CPU ; augmenter QUALITY_TIMEOUT_SECONDS."
        ) from exc
    except httpx.ConnectError as exc:
        raise JudgeUnavailable("Connexion a Ollama impossible. Verifier que Ollama fonctionne et que QUALITY_OLLAMA_URL est correcte.") from exc
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        logger.warning("Ollama HTTP error: status=%s model=%s", status, settings.quality_model)
        if status == 404:
            raise JudgeUnavailable(
                f"Modele Ollama introuvable : {settings.quality_model}. "
                "Verifier ollama list et QUALITY_MODEL."
            ) from exc
        raise JudgeUnavailable(
            f"Ollama a renvoye HTTP {status}. Verifier ses journaux et les ressources disponibles."
        ) from exc
    except httpx.HTTPError as exc:
        raise JudgeUnavailable("La communication avec Ollama a echoue.") from exc
    try:
        return JudgeResult.model_validate_json(response.json()["message"]["content"])
    except (ValueError, KeyError, TypeError, ValidationError) as exc:
        raise InvalidJudgeOutput("Le modèle a produit une évaluation invalide.") from exc

def evaluate(request: QualityRequest) -> QualityScore:
    validate_rubric()
    result = call_judge(request)
    try:
        validate_references(request, result)
    except ValueError as exc:
        logger.warning("Quality references rejected: %s", exc)
        raise InvalidJudgeOutput(f"Les preuves du modele ne sont pas verifiables : {exc}") from exc
    return QualityScore(
        conversation_id=request.conversation_id,
        global_score=compute_global_score(result.scores),
        scores=result.scores, evidence=result.evidence, explanation=result.explanation,
        recommendations=result.recommendations, claims=result.claims,
        flags=QualityFlags(
            unsourced_claims=sum(c.status == "UNSUPPORTED" for c in result.claims),
            unverifiable_claims=sum(c.status == "UNVERIFIABLE" for c in result.claims),
            procedure_violations=result.procedure_violations,
            missing_source_keys=missing_source_keys(request),
        ),
    )
