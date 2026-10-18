"""
Budget d'outils du Customer Advisor, SANS appel LLM reel.

Constat sur Groq reel (2026-10-18) : pour une carte refusee, le modele enchaine quatre outils
(base documentaire, client, etat du systeme, transactions). Le quatrieme tour de decide()
demandait encore un outil, le graphe atteignait MAX_ITERATIONS et finalize() renvoyait une
escalade generique a confiance 0 : le modele n'avait jamais l'occasion de repondre.
Le dernier tour du budget doit donc forcer finalize_response.
"""
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.advisor import nodes
from app.advisor.graph import run_advisor
from app.advisor.nodes import MAX_ITERATIONS, _build_messages, decide
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode


def _make_request() -> AdvisorRequest:
    return AdvisorRequest(
        conversation_id="11111111-1111-1111-1111-111111111111",
        customer_id="22222222-2222-2222-2222-222222222222",
        message="Ma carte a été refusée ce soir chez un commerçant",
        context=AdvisorContext(tenure_months=24),
        mode=Mode.live,
    )


def _response(name: str, args: dict) -> MagicMock:
    response = MagicMock()
    response.tool_calls = [{"name": name, "args": args}]
    return response


_FINAL_ARGS = {
    "action": "respond",
    "reply": "Votre carte est active ; le refus vient du plafond de paiement atteint.",
    "intent": "CARD",
    "confidence": 0.85,
}


@pytest.mark.asyncio
async def test_le_dernier_tour_du_budget_force_finalize_response():
    with patch("app.advisor.nodes._get_decide_llm") as get_llm:
        get_llm.return_value.ainvoke = AsyncMock(return_value=_response("finalize_response", _FINAL_ARGS))
        await decide({"request": _make_request(), "iterations": MAX_ITERATIONS - 1})

    get_llm.assert_called_once_with(final=True)
    sent = get_llm.return_value.ainvoke.await_args.args[0]
    assert "budget" in sent[-1].content.lower()


@pytest.mark.asyncio
async def test_avant_le_dernier_tour_le_modele_choisit_librement():
    with patch("app.advisor.nodes._get_decide_llm") as get_llm:
        get_llm.return_value.ainvoke = AsyncMock(return_value=_response("get_customer", {}))
        await decide({"request": _make_request(), "iterations": 0})

    get_llm.assert_called_once_with(final=False)


def test_le_llm_final_ne_propose_que_finalize_response_et_l_impose():
    nodes._final_llm = None
    with patch("app.advisor.nodes.ChatGroq") as chat:
        nodes._get_decide_llm(final=True)
    nodes._final_llm = None

    tools = chat.return_value.bind_tools.call_args.args[0]
    assert [t["function"]["name"] for t in tools] == ["finalize_response"]
    assert chat.return_value.bind_tools.call_args.kwargs["tool_choice"] == "finalize_response"


def test_les_resultats_d_outils_renvoyes_au_modele_sont_bornes():
    long_result = "x" * 5000
    state = {
        "request": _make_request(),
        "tool_calls": [{"name": "search_knowledge_base", "arguments": {"query": "carte"}, "result": long_result,
                        "error": None}],
    }

    summary = _build_messages(state)[-1].content

    assert len(summary) < nodes.MAX_TOOL_RESULT_CHARS + 300
    assert "tronqué" in summary


@pytest.mark.asyncio
async def test_un_modele_qui_veut_toujours_un_outil_repond_quand_meme_au_client():
    """Bout en bout du graphe : le modele demande un outil a chaque tour libre ; au dernier tour
    il est contraint de conclure, et c'est sa reponse qui part, pas l'escalade generique."""

    def llm_for(final: bool = False):
        llm = MagicMock()
        if final:
            llm.ainvoke = AsyncMock(return_value=_response("finalize_response", _FINAL_ARGS))
        else:
            llm.ainvoke = AsyncMock(return_value=_response("get_transactions", {"limit": 5}))
        return llm

    with patch("app.advisor.nodes._get_decide_llm", side_effect=llm_for), \
            patch("app.advisor.nodes.TOOL_IMPLEMENTATIONS", {"get_transactions": AsyncMock(return_value=[])}):
        response = await run_advisor(_make_request())

    assert response.action.type == "NONE"
    assert response.reply == _FINAL_ARGS["reply"]
    assert response.confidence == 0.85
    assert len(response.tool_calls) == MAX_ITERATIONS - 1
