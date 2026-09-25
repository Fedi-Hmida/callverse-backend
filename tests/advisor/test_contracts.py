from uuid import uuid4
from app.contracts.advisor import AdvisorRequest, AdvisorResponse, AdvisorContext, Mode, Action


def test_advisor_request_minimal():
    req = AdvisorRequest(
        conversation_id=uuid4(),
        customer_id=uuid4(),
        message="Ma carte a été refusée en caisse",
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=24,
            churn_risk="LOW",
        ),
        mode=Mode.live,
    )
    assert req.message.startswith("Ma carte")


def test_advisor_response_minimal():
    resp = AdvisorResponse(
        reply="Je vérifie votre carte.",
        intent="CARD",
        confidence=0.8,
        action=Action(type="NONE"),
        latency_ms=120,
    )
    assert resp.intent == "CARD"
