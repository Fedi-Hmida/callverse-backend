"""
Evaluation reelle de la precision d'appel d'outils, avec le vrai LLM.
Consomme des tokens Groq — a lancer ponctuellement, pas en boucle.

Usage :
  PYTHONPATH=. python3 scripts/evaluate_tool_precision.py         # tous les cas
  PYTHONPATH=. python3 scripts/evaluate_tool_precision.py 4       # les 4 premiers seulement
"""
import asyncio
import sys

from app.advisor.graph import run_advisor
from app.contracts.advisor import AdvisorContext, AdvisorRequest, Mode
from app.quality.tool_precision import ToolCase, evaluate_case, summarize

CUSTOMER_ID = "22222222-2222-2222-2222-222222222222"
CONVERSATION_ID = "44444444-4444-4444-4444-444444444444"

CASES = [
    ToolCase(
        name="opposition_carte_perdue",
        message="J'ai perdu ma carte bancaire, je veux faire opposition.",
        expected_tools={"search_knowledge_base"},
        acceptable_tools={"get_customer"},
    ),
    ToolCase(
        name="transaction_suspecte",
        message="Il y a une transaction de 500 euros que je ne reconnais pas du tout sur mon compte.",
        expected_tools={"search_knowledge_base"},
        acceptable_tools={"get_customer", "get_transactions", "escalate"},
    ),
    ToolCase(
        name="solde_compte",
        message="Quel est le solde actuel de mon compte ?",
        expected_tools={"get_customer"},
        acceptable_tools={"get_transactions"},
    ),
    ToolCase(
        name="derniere_transaction",
        message="Pouvez-vous me confirmer ma dernière transaction ?",
        expected_tools={"get_transactions"},
        acceptable_tools={"get_customer"},
    ),
    ToolCase(
        name="conditions_decouvert",
        message="Quelles sont les conditions de mon découvert autorisé ?",
        expected_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="demande_credit",
        message="Je voudrais faire une demande de crédit, quels documents dois-je fournir ?",
        expected_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="geste_commercial_frais",
        message="J'ai eu des frais bancaires anormaux le mois dernier, pouvez-vous me rembourser ?",
        expected_tools={"apply_credit"},
        acceptable_tools={"search_knowledge_base", "get_customer", "get_transactions"},
    ),
    ToolCase(
        name="cloture_compte",
        message="Je veux fermer mon compte, comment procéder ?",
        expected_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="phishing_signale",
        message="J'ai reçu un SMS bizarre qui demandait mon code de carte, j'ai répondu par erreur.",
        expected_tools={"escalate"},
        acceptable_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="virement_international",
        message="Combien de temps prend un virement vers un compte au Maroc ?",
        expected_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="panne_application",
        message="L'application ne s'ouvre plus depuis ce matin, c'est un bug chez vous ?",
        expected_tools={"check_system_status"},
        acceptable_tools={"search_knowledge_base"},
    ),
    ToolCase(
        name="reclamation_generale",
        message="Je ne suis pas satisfait du traitement de mon dossier, je veux déposer une réclamation.",
        expected_tools={"create_case"},
        acceptable_tools={"search_knowledge_base", "escalate"},
    ),
]


async def run_case(case: ToolCase):
    request = AdvisorRequest(
        conversation_id=CONVERSATION_ID,
        customer_id=CUSTOMER_ID,
        message=case.message,
        context=AdvisorContext(
            contract_type="COMPTE_COURANT",
            tenure_months=24,
            churn_risk="LOW",
        ),
        mode=Mode.live,
    )
    response = await run_advisor(request)
    return evaluate_case(case, response)


async def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else len(CASES)
    cases_to_run = CASES[:limit]

    results = []
    for case in cases_to_run:
        print(f"Running: {case.name}...")
        result = await run_case(case)
        status = "OK" if result.recall_hit else "MISS"
        print(f"  [{status}] attendu={result.expected_tools} appele={result.called_tools} precision={result.precision:.2f}")
        results.append(result)

    print()
    print("--- Resume ---")
    summary = summarize(results)
    print(f"Cas evalues      : {summary['n_cases']}")
    print(f"Rappel           : {summary['recall']:.1%}")
    print(f"Precision moyenne: {summary['avg_precision']:.1%}")


if __name__ == "__main__":
    asyncio.run(main())