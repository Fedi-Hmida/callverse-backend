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
from .sourcing_check import align_reference_indices, missing_source_keys, validate_references

class JudgeUnavailable(RuntimeError):
    pass

class JudgeTimeout(JudgeUnavailable):
    pass

logger = logging.getLogger(__name__)

class InvalidJudgeOutput(RuntimeError):
    pass

def _repair_and_validate_judge_result(raw_content: str, request: QualityRequest) -> JudgeResult:
    # 1. Validation directe
    try:
        return JudgeResult.model_validate_json(raw_content)
    except Exception:
        pass

    # 2. Nettoyage du JSON
    cleaned = raw_content.strip()
    match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", cleaned, re.DOTALL)
    if match:
        cleaned = match.group(1).strip()
    else:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start != -1 and end != -1 and end > start:
            cleaned = cleaned[start : end + 1]

    data = json.loads(cleaned)
    if not isinstance(data, dict):
        raise ValueError("Le contenu parsé n'est pas un dictionnaire JSON.")

    # 3. Scores : la forme est réparée (arrondi, bornes 0-10), jamais le fond. Un score absent
    #    ou illisible rejette l'évaluation : une note inventée (5) passerait pour une vraie note.
    from app.contracts.quality import CODES
    raw_scores = data.get("scores")
    if not isinstance(raw_scores, dict):
        raise ValueError("Le juge n'a fourni aucun score.")
    scores = {}
    for code in CODES:
        val = raw_scores.get(code)
        try:
            scores[code] = max(0, min(10, int(round(float(val)))))
        except (ValueError, TypeError):
            raise ValueError(f"Score absent ou illisible pour le critère {code}.") from None
    data["scores"] = scores

    # 4. Preuves : une par critère, citée depuis un message réel. Aucune preuve de secours :
    #    une preuve manquante ou un index invalide rejettent l'évaluation.
    raw_evidence = data.get("evidence")
    if not isinstance(raw_evidence, list):
        raise ValueError("Le juge n'a fourni aucune preuve.")

    evidence_by_crit = {}
    for item in raw_evidence:
        if isinstance(item, dict) and item.get("criterion") in CODES:
            crit = item["criterion"]
            idx = item.get("message_index")
            if not isinstance(idx, int) or not (0 <= idx < len(request.messages)):
                raise ValueError(f"message_index invalide pour le critère {crit}.")
            if not item.get("evidence"):
                raise ValueError(f"Preuve vide pour le critère {crit}.")
            justif = item.get("justification") or f"Évaluation du critère {crit}."
            evidence_by_crit[crit] = {
                "criterion": crit,
                "score": scores[crit],
                "message_index": idx,
                "evidence": str(item["evidence"]),
                "justification": str(justif)[:200],
            }

    missing = sorted(set(CODES) - evidence_by_crit.keys())
    if missing:
        raise ValueError(f"Preuve absente pour : {', '.join(missing)}.")
    data["evidence"] = [evidence_by_crit[code] for code in sorted(CODES)]

    # 5. Réparer claims
    raw_claims = data.get("claims")
    claims = []
    available = {f"{d.doc_id}/{d.chunk_id}" for d in request.source_documents}
    if isinstance(raw_claims, list):
        for c in raw_claims:
            if isinstance(c, dict) and c.get("excerpt"):
                st = c.get("status")
                if st not in ("SUPPORTED", "UNSUPPORTED", "UNVERIFIABLE"):
                    st = "UNVERIFIABLE"
                idx = c.get("message_index")
                # Une affirmation mal située est écartée, pas redirigée vers un autre message :
                # elle n'est pas une note, la perdre vaut mieux que l'attribuer à tort.
                if not isinstance(idx, int) or not (0 <= idx < len(request.messages)):
                    continue
                if request.messages[idx].sender != "ADVISOR":
                    continue
                s_keys = c.get("source_keys")
                if isinstance(s_keys, list):
                    s_keys = [str(k) for k in s_keys if str(k) in available]
                else:
                    s_keys = []
                claims.append({
                    "message_index": idx,
                    "excerpt": str(c["excerpt"]),
                    "status": st,
                    "source_keys": s_keys,
                    "justification": str(c.get("justification") or "Affirmation extraite."),
                })
    data["claims"] = claims

    # 6. Autres champs obligatoires
    if not isinstance(data.get("procedure_violations"), list):
        data["procedure_violations"] = []
    else:
        data["procedure_violations"] = [str(v) for v in data["procedure_violations"]]

    if not data.get("explanation") or not isinstance(data["explanation"], str):
        data["explanation"] = "Évaluation effectuée selon la grille bancaire."

    if not isinstance(data.get("recommendations"), list):
        data["recommendations"] = []
    valid_data = {k: data[k] for k in JudgeResult.model_fields.keys() if k in data}
    return JudgeResult.model_validate(valid_data)


def call_judge(request: QualityRequest) -> JudgeResult:
    if not settings.quality_model:
        raise JudgeUnavailable("Configurer QUALITY_MODEL avec un modèle installé dans Ollama.")
    context = request.model_dump(mode="json")
    # Make the zero-based indices explicit where the model reads each turn.
    # The public request contract stays unchanged.
    context["messages"] = [dict(message, message_index=i)
                           for i, message in enumerate(context["messages"])]
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
    context["advisor_claim_candidates"] = [candidate for candidate in context["citation_candidates"]
                                           if candidate["sender"] == "ADVISOR"]
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
        raw_output = response.json()["message"]["content"]
    except (KeyError, TypeError) as exc:
        raise InvalidJudgeOutput("Réponse Ollama malformée (pas de champ content).") from exc

    try:
        return _repair_and_validate_judge_result(raw_output, request)
    except (ValueError, KeyError, TypeError, ValidationError) as exc:
        logger.warning("Sortie du juge invalide: %s | brut=%s", exc, raw_output)
        raise InvalidJudgeOutput("Le modèle a produit une évaluation invalide.") from exc

def evaluate(request: QualityRequest) -> QualityScore:
    validate_rubric()
    result = call_judge(request)
    align_reference_indices(request, result)
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
