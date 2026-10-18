"""
Noeuds du graphe LangGraph du Customer Advisor.

- decide  : le LLM choisit d'appeler un outil, de repondre, ou d'escalader
            (function calling, tool_choice="required").
- execute_tool : execute l'outil choisi et trace l'appel pour l'XAI.
- finalize : construit la sortie finale, alignee sur app.contracts.advisor.AdvisorResponse
             (reply, intent, confidence, tool_calls, sources, action, latency_ms).
"""
from __future__ import annotations

import logging
from typing import Literal
from uuid import NAMESPACE_URL, uuid5

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_groq import ChatGroq
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.advisor.kb.retriever import search_knowledge_base
from app.advisor.prompts import ADVISOR_SYSTEM_PROMPT, LOW_CONFIDENCE_THRESHOLD
from app.advisor.state import AdvisorGraphState
from app.advisor.tools import TOOL_IMPLEMENTATIONS, TOOL_SPECS
from app.config import settings
from app.contracts.advisor import Intent, Mode
from app.provider_retry import invoke_with_retry

logger = logging.getLogger(__name__)


class _FinalDecision(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    action: Literal["respond", "escalate"]
    reply: str = Field(min_length=1)
    intent: Intent
    confidence: float = Field(ge=0, le=1)
    reason: str | None = None


def _invalid_decision(state: AdvisorGraphState) -> dict:
    # Provider-generated arguments are untrusted even when function calling is required.
    # Keep the conversation usable and preserve already executed tools for its trace.
    return {"decision": {
        "action": "escalate",
        "reply": "Je préfère transmettre votre demande à un superviseur.",
        "intent": "OTHER", "confidence": 0.0,
        "reason": "le modele n'a produit aucun appel d'outil exploitable",
    }, "iterations": state.get("iterations", 0) + 1}


MAX_ITERATIONS = 4
"""Tours de decide() par message client. Le dernier est reserve a la conclusion (finalize_response
impose) : sans cela, un modele qui enchaine les outils atteignait la limite sans jamais repondre."""

MAX_TOOL_RESULT_CHARS = 1500
"""Longueur maximale d'un resultat d'outil renvoye au modele. Chaque tour renvoie tous les resultats
precedents : sans borne, les extraits documentaires pesaient ~800 jetons par tour, et un seul message
depassait le quota Groq de 8 000 jetons par minute. Les sources transmises au backend ne sont pas tronquees."""

_BUDGET_NOTE = (
    "[Budget d'outils atteint] N'appelle plus d'outil metier : conclus maintenant avec "
    "finalize_response a partir des resultats ci-dessus (respond si tu peux repondre, "
    "escalate sinon)."
)

_FINALIZE_TOOL_SPEC = {
    "name": "finalize_response",
    "description": (
        "Cloture le tour : a utiliser quand tu es pret a repondre au client "
        "ou a escalader, plutot que d'appeler un outil metier supplementaire."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["respond", "escalate"],
                "description": (
                    "respond si tu peux repondre au client ; escalate si le cas "
                    "depasse ton autorite (montant hors plafond, client sensible, "
                    "incertitude significative)."
                ),
            },
            "reply": {
                "type": "string",
                "description": "Message a renvoyer au client (ou message d'attente si escalade).",
            },
            "intent": {
                "type": "string",
                "enum": ["BALANCE", "CARD", "CREDIT", "FRAUD", "ACCOUNT_CLOSURE", "OTHER"],
                "description": "Utilise exactement une valeur autorisee. Pour une question generale sur les comptes, utilise OTHER, jamais ACCOUNTS.",
            },
            "confidence": {
                "type": "number",
                "description": "Confiance dans cette reponse, entre 0 et 1.",
            },
            "reason": {
                "type": ["string", "null"],
                "description": "Raison de l'escalade, uniquement si action=escalate, sinon null ou absent.",
            },
        },
        "required": ["action", "reply", "intent", "confidence"],
    },
}


def _to_openai_tool(spec: dict) -> dict:
    return {
        "type": "function",
        "function": {
            "name": spec["name"],
            "description": spec["description"],
            "parameters": spec["input_schema"],
        },
    }


_decide_llm = None
_final_llm = None
_simulation_llms = {}


def _get_decide_llm(final: bool = False):
    """LLM de decision. final=True : seul finalize_response est propose, et il est impose."""
    global _decide_llm, _final_llm
    if final:
        if _final_llm is None:
            _final_llm = _new_groq().bind_tools(
                [_to_openai_tool(_FINALIZE_TOOL_SPEC)], tool_choice=_FINALIZE_TOOL_SPEC["name"]
            )
        return _final_llm
    if _decide_llm is None:
        all_specs = TOOL_SPECS + [_FINALIZE_TOOL_SPEC]
        tools = [_to_openai_tool(s) for s in all_specs]
        _decide_llm = _new_groq().bind_tools(tools, tool_choice="required")
    return _decide_llm


def _get_simulation_llm(final: bool = False):
    model = settings.llm_simulation_advisor_model.strip() or settings.llm_model_advanced
    key = (model, final)
    if key not in _simulation_llms:
        specs = [_FINALIZE_TOOL_SPEC] if final else TOOL_SPECS + [_FINALIZE_TOOL_SPEC]
        _simulation_llms[key] = _new_groq(model=model).bind_tools(
            [_to_openai_tool(spec) for spec in specs],
            tool_choice=_FINALIZE_TOOL_SPEC["name"] if final else "required",
        )
    return _simulation_llms[key]


def _new_groq(model: str | None = None) -> ChatGroq:
    return ChatGroq(
        model=model or settings.llm_model_advanced,
        api_key=settings.llm_api_key,
        temperature=0.2,
        reasoning_effort="low",
        max_retries=0,
        timeout=20,
        max_tokens=settings.llm_advisor_max_output_tokens,
    )


def _bounded(value) -> str:
    text = str(value)
    if len(text) <= MAX_TOOL_RESULT_CHARS:
        return text
    return text[:MAX_TOOL_RESULT_CHARS] + " […tronqué]"


def _build_messages(state: AdvisorGraphState) -> list:
    request = state["request"]
    messages = [SystemMessage(content=ADVISOR_SYSTEM_PROMPT)]

    # No identifier here: tools receive customer_id and conversation_id from the request
    # (TRUSTED_IDENTIFIERS), so the model never needs, sees or repeats them.
    context_details = [
        f"[Contexte client] anciennete: {request.context.tenure_months} mois"
    ]
    if request.context.segment is not None:
        context_details.append(f"segment: {request.context.segment}")
    if request.context.open_tickets is not None:
        context_details.append(f"tickets ouverts: {request.context.open_tickets}")
    context_note = ", ".join(context_details) + "."
    messages.append(SystemMessage(content=context_note))

    # Bound request growth across dialogue turns. Never truncate the trusted context or
    # the current customer message; explicitly disclose omitted older history to the model.
    selected = []
    remaining = settings.llm_advisor_history_chars
    for h in reversed(request.history[-settings.llm_advisor_history_messages:]):
        if len(h.content) > remaining:
            break
        selected.append(h)
        remaining -= len(h.content)
    if len(selected) != len(request.history):
        messages.append(SystemMessage(content="[Historique ancien omis pour limiter le contexte. Ne suppose aucun fait absent.]"))
    for h in reversed(selected):
        if h.role.value == "customer":
            messages.append(HumanMessage(content=h.content))
        else:
            messages.append(AIMessage(content=h.content))

    messages.append(HumanMessage(content=request.message))

    already_called = state.get("tool_calls", [])
    if already_called:
        summary = "; ".join(
            f"{c['name']}({c['arguments']}) -> {_bounded(c.get('result') or c.get('error'))}"
            for c in already_called
        )
        messages.append(SystemMessage(content=f"[Outils deja appeles ce tour] {summary}"))

    return messages


async def decide(state: AdvisorGraphState) -> dict:
    from groq import BadRequestError

    final = state.get("iterations", 0) >= MAX_ITERATIONS - 1
    simulation = state["request"].mode == Mode.simulation
    llm = _get_simulation_llm(final=final) if simulation else _get_decide_llm(final=final)
    model = (settings.llm_simulation_advisor_model.strip() if simulation else "") or settings.llm_model_advanced
    messages = _build_messages(state)
    if final:
        messages.append(SystemMessage(content=_BUDGET_NOTE))

    response = None
    for _attempt in range(2):
        try:
            response = await invoke_with_retry(llm, messages, model=model)
            break
        except BadRequestError as exc:
            # Groq rejette l'appel si le modele repond en texte au lieu d'appeler un outil
            if "tool_use_failed" not in str(exc):
                raise
            # Repeating the same rejected arguments produced the same schema error in
            # real Groq calls (notably intent=ACCOUNTS). Give bounded corrective feedback
            # without echoing untrusted provider text or replaying a completed tool.
            messages.append(SystemMessage(content=(
                "Le dernier appel a ete refuse car il ne respecte pas le schema des outils. "
                "Corrige les arguments. Pour finalize_response : action vaut respond ou escalate ; "
                "intent vaut exactement BALANCE, CARD, CREDIT, FRAUD, ACCOUNT_CLOSURE ou OTHER. "
                "Pour toute autre intention, utilise OTHER (jamais ACCOUNTS). "
                "confidence est un nombre entre 0 et 1 et reply est un texte non vide. "
                "Conserve les resultats des outils deja executes et conclus si tu peux repondre."
            )))

    if response is None or not response.tool_calls:
        # filet de securite si le modele ne respecte pas tool_choice="required"
        return _invalid_decision(state)

    call = response.tool_calls[0]
    if not isinstance(call, dict):
        return _invalid_decision(state)
    tool_name = call.get("name")
    args = call.get("args", {})
    if not isinstance(args, dict):
        return _invalid_decision(state)

    if tool_name == "finalize_response":
        try:
            decision = _FinalDecision.model_validate(args).model_dump(exclude_none=True)
        except ValidationError:
            return _invalid_decision(state)
        if decision["action"] == "escalate":
            decision["reason"] = decision.get("reason") or "incertitude de l'agent"
    else:
        if tool_name not in {spec["name"] for spec in TOOL_SPECS}:
            return _invalid_decision(state)
        decision = {
            "action": "call_tool",
            "tool_name": tool_name,
            "arguments": args,
        }

    return {"decision": decision, "iterations": state.get("iterations", 0) + 1}


TRUSTED_IDENTIFIERS: dict[str, tuple[str, ...]] = {
    "get_customer": ("customer_id",),
    "get_transactions": ("customer_id",),
    "create_case": ("customer_id", "conversation_id"),
    "apply_credit": ("customer_id", "conversation_id"),
}
"""Arguments taken from the backend's request, never from the LLM: a customer message cannot
steer a tool towards another customer or conversation. They are absent from TOOL_SPECS."""


def _with_trusted_identifiers(tool_name: str, arguments: dict, request) -> dict:
    trusted = dict(arguments)
    for identifier in TRUSTED_IDENTIFIERS.get(tool_name, ()):
        trusted[identifier] = str(getattr(request, identifier))
    if tool_name == "apply_credit":
        # One idempotency identity per trusted customer turn, even if the model repeats
        # the tool or changes its amount. Neither an LLM nor SDK retry chooses this key.
        previous = request.history[-1].ts.isoformat() if request.history else "first-turn"
        trusted["request_id"] = str(uuid5(NAMESPACE_URL,
            f"{request.conversation_id}:{previous}:{request.message}"))
    return trusted


async def execute_tool(state: AdvisorGraphState) -> dict:
    decision = state["decision"]
    tool_name = decision["tool_name"]
    arguments = _with_trusted_identifiers(tool_name, decision.get("arguments") or {}, state["request"])
    if state["request"].mode == Mode.simulation and tool_name != "search_knowledge_base":
        call = {
            "name": tool_name,
            "arguments": arguments,
            "result": None,
            "error": "Backend tools are disabled in simulation mode.",
        }
        return {"tool_calls": list(state.get("tool_calls", [])) + [call]}

    call = {"name": tool_name, "arguments": arguments, "result": None, "error": None}
    retrieved_documents = list(state.get("retrieved_documents", []))

    try:
        if tool_name == "search_knowledge_base":
            result = await search_knowledge_base(**arguments)
            retrieved_documents.extend(result)
        else:
            implementation = TOOL_IMPLEMENTATIONS[tool_name]
            result = await implementation(**arguments)
        call["result"] = result
    except Exception as exc:  # noqa: BLE001
        logger.exception("Tool call failed: %s", tool_name)
        call["error"] = str(exc)

    tool_calls = list(state.get("tool_calls", [])) + [call]
    return {"tool_calls": tool_calls, "retrieved_documents": retrieved_documents}


def route_after_decide(state: AdvisorGraphState) -> str:
    decision = state["decision"]
    if state.get("iterations", 0) >= MAX_ITERATIONS:
        return "finalize"
    if decision["action"] == "call_tool":
        return "execute_tool"
    return "finalize"


def _tool_call_to_contract_shape(call: dict) -> dict:
    """Convertit un tool_call interne vers la forme attendue par ToolCall du contrat."""
    return {
        "tool": call["name"],
        "args": call.get("arguments", {}),
        "result_summary": str(call.get("result") or call.get("error") or ""),
        "ok": call.get("error") is None,
    }


def _document_to_source_shape(doc: dict) -> dict | None:
    """Convertit un document recupere (forme renvoyee par
    app.advisor.kb.retriever.search_knowledge_base, cle 'article_id')
    vers la forme attendue par Source du contrat ('kb_article_id').
    Retourne None si le document n'a pas d'identifiant exploitable (a ignorer
    plutot qu'a faire planter la validation du contrat)."""
    kb_article_id = doc.get("article_id") or doc.get("kb_article_id") or doc.get("doc_id")
    if not kb_article_id:
        return None
    return {"kb_article_id": kb_article_id, "score": doc.get("score", 0.0)}


def _dedupe_sources_keep_best_score(sources: list[dict]) -> list[dict]:
    """Un meme article peut apparaitre plusieurs fois (un chunk par occurrence).
    On ne garde qu'une ligne par kb_article_id, celle au score le plus eleve,
    et on trie par score decroissant pour l'affichage XAI."""
    best: dict[str, dict] = {}
    for s in sources:
        key = str(s["kb_article_id"])
        if key not in best or s["score"] > best[key]["score"]:
            best[key] = s
    return sorted(best.values(), key=lambda s: s["score"], reverse=True)


async def finalize(state: AdvisorGraphState) -> dict:
    decision = state["decision"]
    retrieved_documents = state.get("retrieved_documents", [])
    tool_calls = state.get("tool_calls", [])

    sources = _dedupe_sources_keep_best_score(
        [s for s in (_document_to_source_shape(d) for d in retrieved_documents) if s is not None]
    )
    contract_tool_calls = [_tool_call_to_contract_shape(c) for c in tool_calls]
    intent = decision.get("intent", "OTHER")

    # call_tool ici : budget epuise sans conclusion (le tour final impose n'a pas abouti).
    if decision["action"] in ("escalate", "call_tool"):
        return {
            "reply": decision.get("reply", "Je transmets votre demande à un superviseur."),
            "intent": intent,
            "confidence": decision.get("confidence", 0.0),
            "action_type": "ESCALATE",
            "action_payload": {"reason": decision.get("reason") or "incertitude de l'agent"},
            "tool_calls": contract_tool_calls,
            "retrieved_documents": sources,
        }

    confidence = decision.get("confidence", 0.0)

    if confidence < LOW_CONFIDENCE_THRESHOLD:
        return {
            "reply": "Je préfère transmettre votre demande à un superviseur.",
            "intent": intent,
            "confidence": confidence,
            "action_type": "ESCALATE",
            "action_payload": {"reason": "confiance sous le seuil"},
            "tool_calls": contract_tool_calls,
            "retrieved_documents": sources,
        }

    return {
        "reply": decision["reply"],
        "intent": intent,
        "confidence": confidence,
        "action_type": "NONE",
        "action_payload": {},
        "tool_calls": contract_tool_calls,
        "retrieved_documents": sources,
    }
