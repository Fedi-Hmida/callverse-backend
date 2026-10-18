"""Juge qualité (Groq par défaut, Ollama en option) et assemblage déterministe du résultat."""
import json
import logging
import re
from copy import deepcopy
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
        except (ValueError, TypeError, OverflowError):
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


GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"


def call_judge(request: QualityRequest) -> JudgeResult:
    provider = settings.quality_provider.strip().lower()
    if provider == "groq":
        if not settings.llm_api_key:
            raise JudgeUnavailable("Configurer llm_api_key pour le juge qualité Groq.")
        ask = _ask_groq
    elif provider == "ollama":
        if not settings.quality_model:
            raise JudgeUnavailable("Configurer QUALITY_MODEL avec un modèle installé dans Ollama.")
        ask = _ask_ollama
    else:
        raise JudgeUnavailable(f"QUALITY_PROVIDER inconnu : {settings.quality_provider!r} (groq ou ollama).")
    raw_output = ask(_judge_context(request))
    if not isinstance(raw_output, str) or not raw_output.strip():
        raise InvalidJudgeOutput("Le modèle n'a fourni aucun texte d'évaluation.")
    try:
        if provider == "groq":
            data = json.loads(raw_output)
            if isinstance(data, dict) and isinstance(data.get("evidence"), dict):
                data["evidence"] = [dict(proof, criterion=code, score=data["scores"][code])
                                    for code, proof in data["evidence"].items()]
                raw_output = json.dumps(data, ensure_ascii=False)
        return _repair_and_validate_judge_result(raw_output, request)
    except (ValueError, KeyError, TypeError, ValidationError) as exc:
        logger.warning("Sortie du juge invalide: %s | brut=%s", exc, raw_output)
        raise InvalidJudgeOutput("Le modèle a produit une évaluation invalide.") from exc


def _judge_context(request: QualityRequest) -> dict:
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
    return context


def _groq_format(context: dict) -> dict:
    if settings.quality_groq_model not in ("openai/gpt-oss-20b", "openai/gpt-oss-120b"):
        return {"type": "json_object"}
    from app.contracts.quality import CODES
    schema = deepcopy(JudgeResult.model_json_schema())
    schema["properties"]["scores"] = {
        "type": "object", "properties": {code: {"type": "integer"} for code in sorted(CODES)},
        "required": sorted(CODES), "additionalProperties": False}
    quotes = list(dict.fromkeys(excerpt[:350]
        for candidate in context.get("citation_candidates", [])
        for excerpt in candidate["excerpts"] if excerpt.strip()))
    # Fixed keys guarantee one genuine model-produced quote for every criterion.
    proof = {"type": "object", "properties": {
        "message_index": {"type": "integer"}, "evidence": {"type": "string", **({"enum": quotes} if quotes else {})},
        "justification": {"type": "string"}},
        "required": ["message_index", "evidence", "justification"], "additionalProperties": False}
    schema["properties"]["evidence"] = {
        "type": "object", "properties": {code: deepcopy(proof) for code in sorted(CODES)},
        "required": sorted(CODES), "additionalProperties": False}
    schema["$defs"].pop("CriterionScore", None)
    advisor_quotes = list(dict.fromkeys(excerpt[:350]
        for candidate in context.get("advisor_claim_candidates", [])
        for excerpt in candidate["excerpts"] if excerpt.strip()))
    if advisor_quotes:
        schema["$defs"]["ClaimAssessment"]["properties"]["excerpt"]["enum"] = advisor_quotes
    if not context.get("available_source_keys") and not (context.get("advisor_response") or {}).get("tool_calls"):
        schema["$defs"]["ClaimAssessment"]["properties"]["status"]["enum"] = ["UNVERIFIABLE"]
    return {"type": "json_schema", "json_schema": {
        "name": "callverse_quality", "strict": True, "schema": schema}}


def _ask_groq(context: dict) -> str:
    """Use constrained output on the configured GPT-OSS models, then validate citations locally."""
    response_format = _groq_format(context)
    provider_context = dict(context)
    if response_format["type"] == "json_schema":
        provider_context.pop("output_schema", None)
    prompt = SYSTEM_PROMPT
    if response_format["type"] == "json_schema":
        prompt += "\nFormat Groq : evidence est un objet dont les six clés sont les critères. " \
                  "Chaque valeur contient message_index, evidence et justification. Les notes sont dans scores. " \
                  "Sans documents ni résultats d'outils, les affirmations factuelles sont UNVERIFIABLE."
    try:
        with httpx.Client(timeout=httpx.Timeout(settings.quality_timeout_seconds, connect=10.0)) as client:
            response = client.post(
                GROQ_CHAT_URL,
                headers={"Authorization": f"Bearer {settings.llm_api_key}"},
                json={"model": settings.quality_groq_model, "temperature": 0,
                      "response_format": response_format,
                      "messages": [{"role": "system", "content": prompt},
                                   {"role": "user", "content": json.dumps(provider_context, ensure_ascii=False)}]},
            )
            response.raise_for_status()
    except httpx.TimeoutException as exc:
        logger.warning("Groq judge timeout: model=%s", settings.quality_groq_model)
        raise JudgeTimeout(
            f"Groq n'a pas termine l'evaluation dans le delai de {settings.quality_timeout_seconds:g} secondes."
        ) from exc
    except httpx.ConnectError as exc:
        raise JudgeUnavailable("Connexion a Groq impossible. Verifier l'acces reseau.") from exc
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        logger.warning("Groq judge HTTP error: status=%s model=%s", status, settings.quality_groq_model)
        # Groq can reject its own JSON formatting while returning the actual generation.
        # Pass only that model output through our existing schema and citation checks;
        # never replace missing scores or evidence with invented values.
        if status == 400:
            try:
                error = exc.response.json().get("error", {})
            except (ValueError, AttributeError):
                error = {}
            if not isinstance(error, dict):
                error = {}
            generation = error.get("failed_generation")
            if error.get("code") == "json_validate_failed" and isinstance(generation, str) and generation.strip():
                return generation
        if status == 429:
            raise JudgeUnavailable("Groq : quota de jetons atteint, reessayer plus tard.") from exc
        raise JudgeUnavailable(f"Groq a renvoye HTTP {status}.") from exc
    except httpx.HTTPError as exc:
        raise JudgeUnavailable("La communication avec Groq a echoue.") from exc

    try:
        content = response.json()["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise InvalidJudgeOutput("Réponse Groq malformée (pas de champ content).") from exc
    if not isinstance(content, str) or not content.strip():
        raise InvalidJudgeOutput("Réponse Groq malformée (content doit être un texte non vide).")
    return content


def _ask_ollama(context: dict) -> str:
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
        content = response.json()["message"]["content"]
    except (ValueError, KeyError, TypeError) as exc:
        raise InvalidJudgeOutput("Réponse Ollama malformée (pas de champ content).") from exc
    if not isinstance(content, str) or not content.strip():
        raise InvalidJudgeOutput("Réponse Ollama malformée (content doit être un texte non vide).")
    return content

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
