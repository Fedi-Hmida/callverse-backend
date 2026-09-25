"""
Tests de la logique deterministe du graphe, SANS appel LLM reel
(evite de consommer le quota Groq a chaque lancement de la suite).
"""
from unittest.mock import patch, MagicMock

from app.simulator.graph import evaluer_reponse_recue, decider_action


def test_evaluation_ignoree_si_pas_encore_de_reponse_conseiller():
    state = {
        "profile": "churn",
        "patience": 80.0,
        "satisfaction": 50.0,
        "objective": "obtenir un remboursement",
        "messages": [
            {"role": "customer", "content": "J'ai un souci avec mes frais."}
        ],
        "status": "en_cours",
    }
    result = evaluer_reponse_recue(state)
    assert result["patience"] == 80.0
    assert result["satisfaction"] == 50.0


def test_reponse_concrete_augmente_satisfaction():
    state = {
        "profile": "calme",
        "patience": 90.0,
        "satisfaction": 50.0,
        "objective": "comprendre un frais",
        "messages": [
            {"role": "customer", "content": "Pourquoi 12 euros de frais ?"},
            {"role": "advisor", "content": "Ce sont des frais de tenue de compte de 12 euros, prelevés le 5 de chaque mois selon votre contrat."},
        ],
        "status": "en_cours",
    }
    result = evaluer_reponse_recue(state)
    assert result["satisfaction"] > 50.0


def test_abandon_sous_le_seuil_de_patience():
    state = {
        "profile": "impatient",
        "patience": 10.0,
        "satisfaction": 30.0,
        "objective": "debloquer un virement",
        "messages": [],
        "status": "en_cours",
    }
    result = decider_action(state)
    assert result["status"] == "abandonne"


def test_resolution_si_satisfaction_haute():
    state = {
        "profile": "calme",
        "patience": 60.0,
        "satisfaction": 90.0,
        "objective": "comprendre un frais",
        "messages": [],
        "status": "en_cours",
    }
    result = decider_action(state)
    assert result["status"] == "resolu"
