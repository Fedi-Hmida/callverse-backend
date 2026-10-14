from app.simulator.graph import build_graph


def test_reponse_utile_ameliore_satisfaction():
    graph = build_graph()

    initial_state = {
        "profile": "churn",
        "patience": 80.0,
        "satisfaction": 50.0,
        "objective": "faire opposition sur une carte suite a une transaction suspecte",
        "messages": [
            {
                "role": "customer",
                "content": "Bonjour, je vois une transaction de 340 euros que je ne reconnais pas sur mon compte, il faut bloquer ma carte immediatement.",
            },
            {
                "role": "advisor",
                "content": "Je comprends votre inquietude. Je bloque votre carte a l'instant et j'ouvre un dossier de contestation pour la transaction de 340 euros. Vous recevrez une nouvelle carte sous 5 jours.",
            },
        ],
        "status": "en_cours",
    }

    result = graph.invoke(initial_state)

    print("--- Resultat apres un tour ---")
    print("Statut:", result["status"])
    print("Patience:", result["patience"])
    print("Satisfaction:", result["satisfaction"])
    print("Dernier message client:", result["messages"][-1]["content"])

    assert result["satisfaction"] > 50.0
