"""
Teste le Simulator sur plusieurs tours enchaines, comme le ferait le
vrai backend : appelle le graphe, recupere le message client (si
present), simule une reponse d'Advisor fictive, et rappelle avec
l'historique enrichi -- jusqu'a resolu/abandonne ou un nombre max
de tours.
"""
import asyncio

from app.contracts.simulator import CustomerProfile
from app.simulator.graph import build_graph, generer_message

MAX_TOURS = 6

FAKE_ADVISOR_REPLIES = [
    "Bonjour, comment puis-je vous aider ?",
    "Je comprends votre situation, je vérifie votre dossier tout de suite.",
    "J'ai bien noté votre demande, je bloque votre carte immédiatement et j'ouvre un dossier de contestation pour le montant de 340 euros.",
    "Le remboursement sera crédité sous 48 heures sur votre compte.",
    "Y a-t-il autre chose pour vous aider aujourd'hui ?",
    "Je vous remercie de votre patience.",
]


async def run_multi_tours(profile: CustomerProfile, objective: str):
    graph = build_graph()

    state = {
        "profile": profile.value,
        "patience": 80.0,
        "satisfaction": 50.0,
        "objective": objective,
        "messages": [{"role": "advisor", "content": FAKE_ADVISOR_REPLIES[0]}],
        "status": "en_cours",
    }

    print(f"=== Profil: {profile.value} | Objectif: {objective} ===\n")
    print(f"Tour 0 (accueil) :\n  Advisor  : {FAKE_ADVISOR_REPLIES[0]}\n")

    # premier message client genere separement (l'accueil n'a rien a evaluer)
    state = generer_message(state)
    print(f"Tour 1 :\n  Client   : {state['messages'][-1]['content']}\n")

    for tour in range(2, MAX_TOURS + 1):
        advisor_reply = FAKE_ADVISOR_REPLIES[min(tour - 1, len(FAKE_ADVISOR_REPLIES) - 1)]
        state = {
            **state,
            "messages": state["messages"] + [{"role": "advisor", "content": advisor_reply}],
        }
        print(f"  Advisor  : {advisor_reply}")

        result = await graph.ainvoke(state)

        print(f"Tour {tour} :")
        print(f"  Patience : {result['patience']:.1f} | Satisfaction : {result['satisfaction']:.1f} | Statut : {result['status']}")

        if result["status"] != "en_cours":
            print(f"\n>>> Conversation terminee au tour {tour} : {result['status']}\n")
            return

        print(f"  Client   : {result['messages'][-1]['content']}\n")
        state = result
    else:
        print(f"\n>>> Nombre max de tours ({MAX_TOURS}) atteint sans resolution/abandon\n")


async def main():
    await run_multi_tours(
        CustomerProfile.churn,
        "faire opposition sur une carte suite a une transaction suspecte",
    )


asyncio.run(main())