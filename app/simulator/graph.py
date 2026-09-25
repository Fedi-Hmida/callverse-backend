"""
StateGraph LangGraph — Client Simulator.
Sequence : evaluer_reponse_recue -> decider_action -> generer_message
(si en_cours), boucle geree en externe par l'appelant (un seul tour
par invocation du graphe).
"""

from langchain_groq import ChatGroq
from langgraph.graph import StateGraph, END

from app.config import settings
from app.simulator.profiles import PROFILES
from app.simulator.prompts import build_system_prompt
from .state import SimulatorState


def _get_llm(max_tokens: int = 400) -> ChatGroq:
    return ChatGroq(
        model=settings.llm_model_light,
        api_key=settings.llm_api_key,
        temperature=0.7,
        max_retries=1,
        timeout=15,
        max_tokens=max_tokens,
    )


def generer_message(state: SimulatorState) -> SimulatorState:
    """Le LLM formule UNIQUEMENT le texte du message client."""
    system_prompt = build_system_prompt(
        profile_key=state["profile"],
        objective=state["objective"],
        patience=state["patience"],
        satisfaction=state["satisfaction"],
    )

    # ne garder que les 3 derniers messages NON VIDES pour le contexte,
    # pour eviter qu'un silence precedent ne pousse le LLM a se taire
    # a nouveau (effet d'auto-perpetuation observe empiriquement)
    non_empty_messages = [m for m in state["messages"] if m["content"].strip()]
    history_text = "\n".join(
        f"{m['role']}: {m['content']}" for m in non_empty_messages[-3:]
    )

    prompt = (
        f"{system_prompt}\n\nHistorique récent:\n{history_text}\n\n"
        f"Écris ton prochain message, TOUJOURS en français."
    )

    content = _get_llm().invoke(prompt).content.strip()

    if not content:
        # filet de securite : un message vide n'est jamais acceptable,
        # on relance une fois avec une instruction plus explicite
        content = _get_llm(max_tokens=200).invoke(
            f"{prompt}\n\nIMPORTANT: reponds obligatoirement par une phrase "
            f"en français, jamais par un message vide."
        ).content.strip()

    if not content:
        # dernier recours si le LLM persiste a ne rien produire
        content = "..."

    new_messages = state["messages"] + [{"role": "customer", "content": content}]

    return {**state, "messages": new_messages}


def evaluer_reponse_recue(state: SimulatorState) -> SimulatorState:
    """
    Evalue la DERNIERE reponse du conseiller, seulement si le client a
    deja formule une demande au prealable (sinon rien a evaluer : ce
    serait le cas d'un simple message d'accueil).
    """
    config = PROFILES[state["profile"]]
    messages = state["messages"]

    customer_messages = [m for m in messages if m["role"] == "customer"]
    advisor_messages = [m for m in messages if m["role"] == "advisor"]

    # rien à évaluer si le client n'a pas encore parlé, ou si le conseiller
    # n'a pas encore répondu après la dernière prise de parole du client
    if not customer_messages or not advisor_messages:
        return state

    last_advisor_index = max(
        i for i, m in enumerate(messages) if m["role"] == "advisor"
    )
    last_customer_index = max(
        i for i, m in enumerate(messages) if m["role"] == "customer"
    )
    if last_advisor_index < last_customer_index:
        # le conseiller n'a pas encore répondu à la dernière demande
        return state

    last_reply = advisor_messages[-1]["content"]
    is_vague = len(last_reply) < 40 or not any(c.isdigit() for c in last_reply)

    patience = state["patience"]
    satisfaction = state["satisfaction"]

    if is_vague:
        patience -= config.patience_decay_base * config.patience_decay_on_vague
        satisfaction -= config.satisfaction_loss_vague
    else:
        patience -= config.patience_decay_base
        satisfaction += config.satisfaction_gain_useful

    patience = max(0.0, min(100.0, patience))
    satisfaction = max(0.0, min(100.0, satisfaction))

    return {**state, "patience": patience, "satisfaction": satisfaction}


def decider_action(state: SimulatorState) -> SimulatorState:
    """Decision deterministe de statut, jamais confiee au LLM."""
    config = PROFILES[state["profile"]]

    if state["patience"] <= config.abandon_threshold:
        return {**state, "status": "abandonne"}

    if state["satisfaction"] >= 85.0:
        return {**state, "status": "resolu"}

    return {**state, "status": "en_cours"}


def build_graph() -> StateGraph:
    """
    Un seul tour par invocation, conforme au contrat de l'API
    (POST /ai/simulator/next-message = un message client a la fois).
    C'est le backend qui rappelle l'endpoint pour chaque tour suivant,
    avec l'historique mis a jour. Le graphe ne boucle jamais en interne.

    Ordre : evaluer_reponse_recue (juge la derniere reponse advisor deja
    dans l'historique) -> decider_action (patience/satisfaction ->
    statut) -> si en_cours, generer_message produit le prochain message
    client ; sinon le tour se termine sans nouveau message (abandon/resolu).
    """
    graph = StateGraph(SimulatorState)

    graph.add_node("evaluer_reponse_recue", evaluer_reponse_recue)
    graph.add_node("decider_action", decider_action)
    graph.add_node("generer_message", generer_message)

    graph.set_entry_point("evaluer_reponse_recue")
    graph.add_edge("evaluer_reponse_recue", "decider_action")
    graph.add_conditional_edges(
        "decider_action",
        lambda state: "generer_message" if state["status"] == "en_cours" else END,
        {"generer_message": "generer_message", END: END},
    )
    graph.add_edge("generer_message", END)

    return graph.compile()