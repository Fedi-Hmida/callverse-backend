"""A failed backend tool cannot substantiate a factual advisor claim."""
import pytest

from app.contracts.advisor import AdvisorResponse
from app.quality import judge
from .test_judge import request, result


@pytest.mark.parametrize("ok,summary", [(False, "HTTP 503: backend unavailable"), (True, "   ")])
def test_failed_or_empty_tool_result_cannot_support_a_claim(monkeypatch, ok, summary):
    payload = request()
    payload.source_documents = []
    payload.advisor_response = AdvisorResponse(
        reply=payload.messages[1].content, intent="CARD", confidence=.9,
        action={"type": "NONE"}, latency_ms=0,
        tool_calls=[{"tool": "get_customer", "args": {}, "result_summary": summary, "ok": ok}],
    )
    assessment = result()
    assessment.claims[0].source_keys = []
    assessment.claims[0].justification = "Confirmed by get_customer."
    monkeypatch.setattr(judge, "call_judge", lambda _: assessment)

    with pytest.raises(judge.InvalidJudgeOutput):
        judge.evaluate(payload)


def test_successful_cited_tool_result_can_support_a_claim(monkeypatch):
    payload = request()
    payload.source_documents = []
    payload.advisor_response = AdvisorResponse(
        reply=payload.messages[1].content, intent="CARD", confidence=.9,
        action={"type": "NONE"}, latency_ms=0,
        tool_calls=[{"tool": "get_customer", "args": {},
                     "result_summary": "Card is active; opposition must use the secure channel.", "ok": True}],
    )
    assessment = result()
    assessment.claims[0].source_keys = []
    assessment.claims[0].justification = "Confirmed by get_customer."
    monkeypatch.setattr(judge, "call_judge", lambda _: assessment)

    assert judge.evaluate(payload).flags.unverifiable_claims == 0


@pytest.mark.parametrize("field", ["evidence", "claim"])
@pytest.mark.parametrize("blank_quote", ["   ", "\\n\\t"])
def test_whitespace_only_quote_cannot_attest_evidence(monkeypatch, field, blank_quote):
    assessment = result()
    if field == "evidence":
        assessment.evidence[0].evidence = blank_quote
    else:
        assessment.claims[0].excerpt = blank_quote
    monkeypatch.setattr(judge, "call_judge", lambda _: assessment)

    with pytest.raises(judge.InvalidJudgeOutput):
        judge.evaluate(request())
