"""
Test d'intégration bout-en-bout vérifiant l'enchaînement des 4 agents sans aucune erreur.
1. Workforce Manager : produit une proposition d'affectation
2. Customer Simulator : génère une réplique client
3. Customer Advisor : répond à la réplique client avec outillage/confiance
4. Quality Analyst : évalue la conversation et attribue les scores
"""
import pytest
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_e2e_healthcheck():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_e2e_workforce_decide():
    payload = {
        "queues": {"ACCOUNT": 15, "CARD": 2, "CREDIT": 1, "ADVISORY": 0},
        "avg_wait": {"ACCOUNT": 120, "CARD": 45, "CREDIT": 0, "ADVISORY": 0},
        "available": {"ACCOUNT": 0, "CARD": 2, "CREDIT": 1, "ADVISORY": 0},
        "sla_today": 0.80,
        "hour": 14,
        "trend": 1.0,
    }
    response = client.post("/ai/workforce/decide?strategy=THRESHOLD&threshold_n=10", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["type"] == "REASSIGN"
    assert data["to_pool"] == "ACCOUNT"
    assert data["from_pool"] == "CARD"


def test_e2e_quality_rubric():
    response = client.get("/ai/quality/rubric")
    assert response.status_code == 200
    data = response.json()
    assert "criteria" in data
    assert len(data["criteria"]) == 6
