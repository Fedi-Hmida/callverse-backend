from app.contracts.advisor import Action, AdvisorResponse, ToolCall
from app.quality.tool_precision import ToolCase, evaluate_case, summarize


def _make_response(tool_names: list[str]) -> AdvisorResponse:
    return AdvisorResponse(
        reply="ok",
        intent="CARD",
        confidence=0.9,
        tool_calls=[
            ToolCall(tool=name, args={}, result_summary="", ok=True) for name in tool_names
        ],
        sources=[],
        action=Action(type="NONE"),
        latency_ms=0,
    )


def test_recall_hit_quand_outil_attendu_appele():
    case = ToolCase(name="opposition", message="...", expected_tools={"escalate"})
    response = _make_response(["search_knowledge_base", "escalate"])
    result = evaluate_case(case, response)

    assert result.recall_hit is True


def test_recall_miss_quand_outil_attendu_absent():
    case = ToolCase(name="opposition", message="...", expected_tools={"escalate"})
    response = _make_response(["search_knowledge_base"])
    result = evaluate_case(case, response)

    assert result.recall_hit is False


def test_precision_totale_si_tous_les_appels_pertinents():
    case = ToolCase(name="x", message="...", expected_tools={"get_customer"})
    response = _make_response(["get_customer"])
    result = evaluate_case(case, response)

    assert result.precision == 1.0


def test_precision_partielle_si_appel_hors_sujet():
    case = ToolCase(name="x", message="...", expected_tools={"get_customer"})
    response = _make_response(["get_customer", "apply_credit"])
    result = evaluate_case(case, response)

    assert result.precision == 0.5


def test_acceptable_tools_ne_penalise_pas_la_precision():
    case = ToolCase(
        name="x",
        message="...",
        expected_tools={"search_knowledge_base"},
        acceptable_tools={"get_customer"},
    )
    response = _make_response(["search_knowledge_base", "get_customer"])
    result = evaluate_case(case, response)

    assert result.precision == 1.0


def test_precision_zero_si_aucun_outil_appele():
    case = ToolCase(name="x", message="...", expected_tools={"escalate"})
    response = _make_response([])
    result = evaluate_case(case, response)

    assert result.precision == 0.0
    assert result.recall_hit is False


def test_summarize_agrege_correctement():
    results = [
        evaluate_case(
            ToolCase(name="a", message="", expected_tools={"escalate"}),
            _make_response(["escalate"]),
        ),
        evaluate_case(
            ToolCase(name="b", message="", expected_tools={"escalate"}),
            _make_response(["get_customer"]),
        ),
    ]
    summary = summarize(results)

    assert summary["n_cases"] == 2
    assert summary["recall"] == 0.5