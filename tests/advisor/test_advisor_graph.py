"""
Tests de la logique du graphe Advisor, SANS appel LLM ni backend reel.
On appelle directement les fonctions de noeuds avec un state construit
a la main, en controlant le dict "decision" comme si le LLM (pas encore
implemente dans decide()) l'avait produit.
"""
from unittest.mock import AsyncMock, patch

import pytest

from app.advisor.nodes import execute_tool, finalize, route_after_decide
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode


def _make_request() -> AdvisorRequest:
    return AdvisorRequest(
        conversation_id="11111111-1111-1111-1111-111111111111",
        customer_id="22222222-2222-2222-2222-222222222222",
        message="Ma carte a été refusée en caisse",
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=24,
            churn_risk="LOW",
        ),
        mode=Mode.live,
    )


def test_route_after_decide_vers_finalize_si_respond():
    state = {
        "request": _make_request(),
        "decision": {"action": "respond", "reply": "ok", "confidence": 0.8, "intent": "CARD"},
        "iterations": 1,
    }
    assert route_after_decide(state) == "finalize"


def test_route_after_decide_vers_execute_tool_si_call_tool():
    state = {
        "request": _make_request(),
        "decision": {"action": "call_tool", "tool_name": "get_customer", "arguments": {}},
        "iterations": 1,
    }
    assert route_after_decide(state) == "execute_tool"


def test_route_after_decide_stoppe_a_max_iterations():
    state = {
        "request": _make_request(),
        "decision": {"action": "call_tool", "tool_name": "get_customer", "arguments": {}},
        "iterations": 4,
    }
    assert route_after_decide(state) == "finalize"


@pytest.mark.asyncio
async def test_execute_tool_backend_reussi():
    fake_result = {"customer_id": "22222222-2222-2222-2222-222222222222", "churn_risk": "LOW"}

    with patch(
        "app.advisor.nodes.TOOL_IMPLEMENTATIONS",
        {"get_customer": AsyncMock(return_value=fake_result)},
    ):
        state = {
            "request": _make_request(),
            "decision": {
                "action": "call_tool",
                "tool_name": "get_customer",
                "arguments": {"customer_id": "22222222-2222-2222-2222-222222222222"},
            },
            "iterations": 1,
        }
        result = await execute_tool(state)

    assert len(result["tool_calls"]) == 1
    assert result["tool_calls"][0]["error"] is None
    assert result["tool_calls"][0]["result"] == fake_result


@pytest.mark.asyncio
async def test_execute_tool_kb_ajoute_aux_documents_recuperes():
    fake_docs = [{"article_id": "kb-001", "title": "Opposition carte", "excerpt": "...", "score": 0.91}]

    with patch("app.advisor.nodes.search_knowledge_base", AsyncMock(return_value=fake_docs)):
        state = {
            "request": _make_request(),
            "decision": {
                "action": "call_tool",
                "tool_name": "search_knowledge_base",
                "arguments": {"query": "opposition carte"},
            },
            "iterations": 1,
        }
        result = await execute_tool(state)

    assert result["retrieved_documents"] == fake_docs


@pytest.mark.asyncio
async def test_execute_tool_capture_les_erreurs_sans_planter():
    with patch(
        "app.advisor.nodes.TOOL_IMPLEMENTATIONS",
        {"apply_credit": AsyncMock(side_effect=Exception("CREDIT_LIMIT_EXCEEDED"))},
    ):
        state = {
            "request": _make_request(),
            "decision": {
                "action": "call_tool",
                "tool_name": "apply_credit",
                "arguments": {"customer_id": "x", "amount": 999},
            },
            "iterations": 1,
        }
        result = await execute_tool(state)

    assert result["tool_calls"][0]["error"] == "CREDIT_LIMIT_EXCEEDED"


@pytest.mark.asyncio
async def test_finalize_reponse_normale_confiance_haute():
    state = {
        "request": _make_request(),
        "decision": {"action": "respond", "reply": "Votre carte a été bloquée.", "confidence": 0.9, "intent": "CARD"},
        "iterations": 1,
        "tool_calls": [],
        "retrieved_documents": [],
    }
    result = await finalize(state)

    assert result["action_type"] == "NONE"
    assert result["intent"] == "CARD"
    assert result["confidence"] == 0.9


@pytest.mark.asyncio
async def test_finalize_escalade_si_confiance_basse():
    state = {
        "request": _make_request(),
        "decision": {"action": "respond", "reply": "peut-être...", "confidence": 0.3, "intent": "FRAUD"},
        "iterations": 1,
        "tool_calls": [],
        "retrieved_documents": [],
    }
    result = await finalize(state)

    assert result["action_type"] == "ESCALATE"
    assert result["action_payload"]["reason"] == "confiance sous le seuil"


@pytest.mark.asyncio
async def test_finalize_escalade_explicite_du_llm():
    state = {
        "request": _make_request(),
        "decision": {
            "action": "escalate",
            "reason": "montant hors plafond",
            "confidence": 0.7,
            "intent": "CREDIT",
        },
        "iterations": 1,
        "tool_calls": [],
        "retrieved_documents": [],
    }
    result = await finalize(state)

    assert result["action_type"] == "ESCALATE"
    assert result["action_payload"]["reason"] == "montant hors plafond"


@pytest.mark.asyncio
async def test_finalize_convertit_sources_avec_bon_nom_de_champ():
    """Verifie la correction article_id -> kb_article_id."""
    state = {
        "request": _make_request(),
        "decision": {"action": "respond", "reply": "ok", "confidence": 0.9, "intent": "CARD"},
        "iterations": 1,
        "tool_calls": [],
        "retrieved_documents": [
            {"article_id": "kb-001", "title": "Opposition carte", "excerpt": "...", "score": 0.91}
        ],
    }
    result = await finalize(state)

    assert result["retrieved_documents"] == [{"kb_article_id": "kb-001", "score": 0.91}]


@pytest.mark.asyncio
async def test_finalize_deduplique_sources_par_article_garde_meilleur_score():
    state = {
        "request": _make_request(),
        "decision": {"action": "respond", "reply": "ok", "confidence": 0.9, "intent": "CARD"},
        "iterations": 1,
        "tool_calls": [],
        "retrieved_documents": [
            {"article_id": "kb-001", "title": "A", "excerpt": "...", "score": 0.70},
            {"article_id": "kb-002", "title": "B", "excerpt": "...", "score": 0.55},
            {"article_id": "kb-001", "title": "A", "excerpt": "...", "score": 0.50},
        ],
    }
    result = await finalize(state)

    assert result["retrieved_documents"] == [
        {"kb_article_id": "kb-001", "score": 0.70},
        {"kb_article_id": "kb-002", "score": 0.55},
    ]