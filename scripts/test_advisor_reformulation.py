import asyncio
from app.advisor.graph import run_advisor
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode


async def main():
    request = AdvisorRequest(
        conversation_id="33333333-3333-3333-3333-333333333333",
        customer_id="22222222-2222-2222-2222-222222222222",
        message="Le mois dernier normalement j'avais pas ces frais là, ça vient d'où ce montant en plus sur mon relevé ?",
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=6,
            churn_risk="MEDIUM",
        ),
        mode=Mode.live,
    )

    response = await run_advisor(request)

    print("--- Réponse ---")
    print("Reply:", response.reply)
    print("Intent:", response.intent)
    print("Confidence:", response.confidence)
    print("Action:", response.action)
    print()
    print("--- Tool calls (dans l'ordre) ---")
    for i, tc in enumerate(response.tool_calls, 1):
        print(f"{i}. {tc.tool}({tc.args}) -> ok={tc.ok}")
    print()
    print("Sources:", response.sources)
    print("Latency ms:", response.latency_ms)


asyncio.run(main())