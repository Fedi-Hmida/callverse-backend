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

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_groq import ChatGroq

from app.advisor.kb.retriever import search_knowledge_base
from app.advisor.prompts import ADVISOR_SYSTEM_PROMPT, LOW_CONFIDENCE_THRESHOLD
from app.advisor.state import AdvisorGraphState
from app.advisor.tools import TOOL_IMPLEMENTATIONS, TOOL_SPECS
from app.config import settings

logger = logging.getLogger(__name__)

MAX_ITERATIONS = 4

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
            },
            "confidence": {
                "type": "number",
                "description": "Confiance dans cette reponse, entre 0 et 1.",
            },
            "reason": {
                "type": "string",
                "description": "Raison de l'escalade, uniquement si action=escalate.",
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


def _get_decide_llm():
    llm = ChatGroq(
        model=settings.llm_model_advanced,
        api_key=settings.llm_api_key,
        temperature=0.2,
        max_retries=1,
        timeout=20,
    )
    all_specs = TOOL_SPECS + [_FINALIZE_TOOL_SPEC]
    tools = [_to_openai_tool(s) for s in all_specs]
    return llm.bind_tools(tools, tool_choice="required")


def _build_messages(state: AdvisorGraphState) -> list:
    request = state["request"]
    messages = [SystemMessage(content=ADVISOR_SYSTEM_PROMPT)]

    context_note = (
        f"[Contexte client] customer_id: {request.customer_id} "
        f"(utilise EXACTEMENT cette valeur pour tout appel d'outil necessitant "
        f"customer_id, ne l'invente jamais). "
        f"Type de contrat: {request.context.contract_type}, "
        f"anciennete: {request.context.tenure_months} mois, "
        f"risque de depart: {request.context.churn_risk}, "
        f"tickets ouverts: {request.context.open_tickets}."
    )
    messages.append(SystemMessage(content=context_note))

    for h in request.history:
        if h.role.value == "customer":
            messages.append(HumanMessage(content=h.content))
        else:
            messages.append(AIMessage(content=h.content))

    messages.append(HumanMessage(content=request.message))

    already_called = state.get("tool_calls", [])
    if already_called:
        summary = "; ".join(
            f"{c['name']}({c['arguments']}) -> {c.get('result') or c.get('error')}"
            for c in already_called
        )
        messages.append(SystemMessage(content=f"[Outils deja appeles ce tour] {summary}"))

    return messages


async def decide(state: AdvisorGraphState) -> dict:
    llm = _get_decide_llm()
    messages = _build_messages(state)

    response = await llm.ainvoke(messages)

    if not response.tool_calls:
        # filet de securite si le modele ne respecte pas tool_choice="required"
        decision = {
            "action": "escalate",
            "reply": "Je préfère transmettre votre demande à un superviseur.",
            "intent": "OTHER",
            "confidence": 0.0,
            "reason": "le modele n'a produit aucun appel d'outil exploitable",
        }
        return {"decision": decision, "iterations": state.get("iterations", 0) + 1}

    call = response.tool_calls[0]
    tool_name = call["name"]
    args = call.get("args", {})

    if tool_name == "finalize_response":
        decision = {
            "action": args.get("action", "respond"),
            "reply": args.get("reply", ""),
            "intent": args.get("intent", "OTHER"),
            "confidence": float(args.get("confidence", 0.5)),
        }
        if args.get("action") == "escalate":
            decision["reason"] = args.get("reason", "incertitude de l'agent")
    else:
        decision = {
            "action": "call_tool",
            "tool_name": tool_name,
            "arguments": args,
        }

    return {"decision": decision, "iterations": state.get("iterations", 0) + 1}


async def execute_tool(state: AdvisorGraphState) -> dict:
    decision = state["decision"]
    tool_name = decision["tool_name"]
    arguments = decision.get("arguments", {})

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

    if decision["action"] == "escalate" or state.get("iterations", 0) >= MAX_ITERATIONS:
        return {
            "reply": decision.get("reply", "Je transmets votre demande à un superviseur."),
            "intent": intent,
            "confidence": decision.get("confidence", 0.0),
            "action_type": "ESCALATE",
            "action_payload": {"reason": decision.get("reason", "incertitude de l'agent")},
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