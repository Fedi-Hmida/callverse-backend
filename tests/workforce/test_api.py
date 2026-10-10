"""Validation de l'API et adaptation des observations/actions."""
import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.contracts.workforce import WorkforceState
from app.workforce import policy
from app.workforce.router import router

def payload():
    return {"queues": {"ACCOUNT": 20, "CARD": 3, "CREDIT": 1, "ADVISORY": 0},
            "avg_wait": {"ACCOUNT": 180, "CARD": 60, "CREDIT": 0, "ADVISORY": 0},
            "available": {"ACCOUNT": 0, "CARD": 2, "CREDIT": 1, "ADVISORY": 0},
            "sla_today": 0.75, "hour": 10, "trend": 1}

@pytest.fixture
def client():
    app = FastAPI()
    app.include_router(router, prefix="/ai/workforce")
    return TestClient(app)

def test_observation_units_and_order():
    obs = policy.state_to_observation(WorkforceState(**payload()))
    assert obs.dtype == np.float32
    assert obs.tolist() == [20,3,1,0,3,1,0,0,0,2,1,0,0.75,10,1]

def test_baselines(client):
    response = client.post("/ai/workforce/decide?strategy=THRESHOLD", json=payload())
    assert response.status_code == 200
    assert response.json()["from_pool"] == "CARD"
    assert response.json()["to_pool"] == "ACCOUNT"
    assert response.json()["requires_approval"] is True
    assert client.post("/ai/workforce/decide?strategy=STATIC_FIFO", json=payload()).json()["type"] == "NONE"

@pytest.mark.parametrize("field,value", [
    ("sla_today", 1.5), ("hour", 24), ("trend", -1),
    ("queues", {"FRAUD": 12}), ("available", {"ACCOUNT": -1})])
def test_invalid_states(client, field, value):
    data = payload()
    data[field] = value
    assert client.post("/ai/workforce/decide", json=data).status_code == 422

def test_unknown_strategy(client):
    assert client.post("/ai/workforce/decide?strategy=OTHER", json=payload()).status_code == 422

def test_missing_model_returns_503(client, monkeypatch, tmp_path):
    monkeypatch.setattr(policy.settings, "workforce_model_path", str(tmp_path / "absent.zip"))
    assert client.post("/ai/workforce/decide", json=payload()).status_code == 503

def test_ppo_adaptation(client, monkeypatch):
    class Model:
        def predict(self, observation, deterministic):
            assert deterministic
            return np.array(1), None
    monkeypatch.setattr(policy, "load_policy", lambda _: Model())
    data = payload()
    output = client.post("/ai/workforce/decide", json=data).json()
    assert output["type"] == "REASSIGN"
    assert output["from_pool"] == "CARD"
    assert output["count"] == 1
    assert output["expected_gain"] == {}
    data["available"] = {k: 0 for k in data["available"]}
    assert client.post("/ai/workforce/decide", json=data).json()["type"] == "NONE"

def test_capabilities(client):
    response = client.get("/ai/workforce/capabilities")
    assert response.status_code == 200
    assert len(response.json()["actions"]) == 5
