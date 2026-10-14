"""
Tests du noeud decide(), SANS appel LLM reel : on mocke la sortie de
ChatGroq.ainvoke pour simuler ce que le modele aurait pu repondre.
"""
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.advisor.nodes import decide
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode


def _make_request(message: str = "Ma carte a été refusée en caisse") -> AdvisorRequest:
    return AdvisorRequest(
        conversation_id="11111111-1111-1111-1111-111111111111",
        customer_id="22222222-2222-2222-2222-222222222222",
        message=message,
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=24,
            churn_risk="LOW",
        ),
        mode=Mode.live,
    )


def _fake_llm_response(tool_calls: list[dict]) -> MagicMock:
    response = MagicMock()
    response.tool_calls = tool_calls
    return response


@pytest.mark.asyncio
async def test_decide_appelle_un_outil_metier():
    fake_response = _fake_llm_response(
        [{"name": "get_customer", "args": {"customer_id": "22222222-2222-2222-2222-222222222222"}}]
    )

    with patch("app.advisor.nodes._get_decide_llm") as mock_get_llm:
        mock_get_llm.return_value.ainvoke = AsyncMock(return_value=fake_response)

        state = {"request": _make_request(), "iterations": 0}
        result = await decide(state)

    assert result["decision"]["action"] == "call_tool"
    assert result["decision"]["tool_name"] == "get_customer"
    assert result["iterations"] == 1


@pytest.mark.asyncio
async def test_decide_finalize_respond():
    fake_response = _fake_llm_response(
        [
            {
                "name": "finalize_response",
                "args": {
                    "action": "respond",
                    "reply": "Votre carte a été bloquée immédiatement.",
                    "intent": "CARD",
                    "confidence": 0.9,
                },
            }
        ]
    )

    with patch("app.advisor.nodes._get_decide_llm") as mock_get_llm:
        mock_get_llm.return_value.ainvoke = AsyncMock(return_value=fake_response)

        state = {"request": _make_request(), "iterations": 1}
        result = await decide(state)

    assert result["decision"]["action"] == "respond"
    assert result["decision"]["intent"] == "CARD"
    assert result["decision"]["confidence"] == 0.9


@pytest.mark.asyncio
async def test_decide_finalize_escalate():
    fake_response = _fake_llm_response(
        [
            {
                "name": "finalize_response",
                "args": {
                    "action": "escalate",
                    "reply": "Je transmets votre demande.",
                    "intent": "CREDIT",
                    "confidence": 0.4,
                    "reason": "montant hors plafond",
                },
            }
        ]
    )

    with patch("app.advisor.nodes._get_decide_llm") as mock_get_llm:
        mock_get_llm.return_value.ainvoke = AsyncMock(return_value=fake_response)

        state = {"request": _make_request(), "iterations": 1}
        result = await decide(state)

    assert result["decision"]["action"] == "escalate"
    assert result["decision"]["reason"] == "montant hors plafond"


@pytest.mark.asyncio
async def test_decide_filet_de_securite_si_aucun_tool_call():
    fake_response = _fake_llm_response([])

    with patch("app.advisor.nodes._get_decide_llm") as mock_get_llm:
        mock_get_llm.return_value.ainvoke = AsyncMock(return_value=fake_response)

        state = {"request": _make_request(), "iterations": 1}
        result = await decide(state)

    assert result["decision"]["action"] == "escalate"
    assert result["decision"]["confidence"] == 0.0