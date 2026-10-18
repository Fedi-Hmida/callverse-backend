from uuid import uuid4

from app.advisor.nodes import _build_messages
from app.advisor.state import AdvisorGraphState
from app.contracts.advisor import AdvisorContext, AdvisorRequest, ChurnRisk, Mode


def _request(context: AdvisorContext) -> AdvisorRequest:
    return AdvisorRequest(
        conversation_id=uuid4(),
        customer_id=uuid4(),
        message="Question client",
        context=context,
        mode=Mode.live,
    )


def test_backend_context_accepts_only_known_fields():
    context = AdvisorContext(tenure_months=24, segment="AFFLUENT")

    assert context.contract_type is None
    assert context.churn_risk is None
    assert context.open_tickets is None
    assert context.segment == "AFFLUENT"


def test_legacy_context_fields_remain_accepted_but_are_not_prompt_dependencies():
    request = _request(
        AdvisorContext(
            contract_type="LEGACY_CONTRACT",
            tenure_months=24,
            churn_risk=ChurnRisk.high,
            open_tickets=2,
            segment="AFFLUENT",
        )
    )
    messages = _build_messages(
        AdvisorGraphState(request=request, iterations=0)
    )
    context_note = messages[1].content

    assert "anciennete: 24 mois" in context_note
    assert "segment: AFFLUENT" in context_note
    assert "tickets ouverts: 2" in context_note
    assert "LEGACY_CONTRACT" not in context_note
    assert "HIGH" not in context_note
