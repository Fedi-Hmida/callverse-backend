import asyncio
from app.advisor.graph import run_advisor
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode


async def main():
    request = AdvisorRequest(
        conversation_id="11111111-1111-1111-1111-111111111111",
        customer_id="22222222-2222-2222-2222-222222222222",
        message="Comment faire opposition sur ma carte bancaire, je l'ai perdue ?",
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=18,
            churn_risk="LOW",
        ),
        mode=Mode.live,
    )

    response = await run_advisor(request)

    print("--- Réponse ---")
    print("Reply:", response.reply)
    print("Intent:", response.intent)
    print("Confidence:", response.confidence)
    print("Action:", response.action)
    print("Tool calls:", response.tool_calls)
    print("Sources:", response.sources)
    print("Latency ms:", response.latency_ms)


asyncio.run(main())