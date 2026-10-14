"""
Tests de l'ordre des noeuds et de la logique multi-tours du Simulator,
SANS appel LLM (on appelle directement evaluer_reponse_recue et
decider_action avec un state construit a la main).

Couvre le bug corrige : l'ordre evaluer -> decider -> generer (et non
generer -> evaluer) est essentiel pour que patience/satisfaction
evoluent reellement d'un tour a l'autre.
"""
from app.simulator.graph import decider_action, evaluer_reponse_recue


def _base_state(**overrides):
    state = {
        "profile": "churn",
        "patience": 80.0,
        "satisfaction": 50.0,
        "objective": "faire opposition sur une carte",
        "messages": [],
        "status": "en_cours",
    }
    state.update(overrides)
    return state


def test_evaluation_ignoree_si_advisor_pas_encore_repondu():
    """
    Reproduit le bug corrige : si le dernier message est un message
    client (pas encore de reponse advisor), rien ne doit etre evalue.
    """
    state = _base_state(
        messages=[
            {"role": "advisor", "content": "Bonjour, comment puis-je vous aider ?"},
            {"role": "customer", "content": "J'ai un souci avec ma carte."},
        ]
    )
    result = evaluer_reponse_recue(state)

    assert result["patience"] == 80.0
    assert result["satisfaction"] == 50.0


def test_evaluation_se_declenche_apres_reponse_advisor():
    """
    Cas nominal post-correction : le dernier message est une reponse
    advisor faisant suite a une demande client -> evaluation reelle.
    """
    state = _base_state(
        messages=[
            {"role": "advisor", "content": "Bonjour, comment puis-je vous aider ?"},
            {"role": "customer", "content": "J'ai vu une transaction suspecte, bloquez ma carte."},
            {"role": "advisor", "content": "Je bloque votre carte immediatement, dossier ouvert pour 340 euros."},
        ]
    )
    result = evaluer_reponse_recue(state)

    # reponse concrete (>40 caracteres, contient un chiffre) -> satisfaction monte
    assert result["satisfaction"] > 50.0
    assert result["patience"] < 80.0  # decroissance normale, meme sur bonne reponse


def test_sequence_multi_tours_patience_decroit_sur_reponses_vagues():
    """
    Simule 3 tours de reponses vagues d'affilee et verifie que la
    patience decroit strictement a chaque tour (jamais figee).
    """
    state = _base_state()
    patiences = [state["patience"]]

    messages = [{"role": "advisor", "content": "Bonjour, comment puis-je vous aider ?"}]
    for i in range(3):
        messages = messages + [
            {"role": "customer", "content": f"Relance numero {i}."},
            {"role": "advisor", "content": "On regarde ca."},  # reponse vague, <40 car, pas de chiffre
        ]
        state = {**state, "messages": messages}
        state = evaluer_reponse_recue(state)
        state = decider_action(state)
        patiences.append(state["patience"])

    # chaque tour doit faire baisser la patience par rapport au precedent
    for i in range(1, len(patiences)):
        assert patiences[i] < patiences[i - 1], (
            f"La patience n'a pas decru au tour {i} : {patiences}"
        )


def test_decider_action_bascule_en_abandonne_sous_le_seuil():
    state = _base_state(patience=5.0)  # sous le seuil churn (20.0)
    result = decider_action(state)
    assert result["status"] == "abandonne"


def test_decider_action_bascule_en_resolu_si_satisfaction_haute():
    state = _base_state(satisfaction=90.0, patience=50.0)
    result = decider_action(state)
    assert result["status"] == "resolu"


def test_decider_action_reste_en_cours_dans_la_zone_intermediaire():
    state = _base_state(patience=50.0, satisfaction=50.0)
    result = decider_action(state)
    assert result["status"] == "en_cours"