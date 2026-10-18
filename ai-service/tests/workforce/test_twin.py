"""
Runs du jumeau numérique : un épisode du simulateur de centre d'appels, pour un scénario
(charge, durée, effectif) et une stratégie de pilotage, avec des KPI reproductibles.
"""
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.config import settings
from app.workforce import twin
from app.workforce.evaluate import evaluate_models
from app.workforce.router import router


def _run(strategy="THRESHOLD", seed=7, load="MEDIUM", minutes=120, advisors=3):
    return twin.run_episode(strategy, seed, twin.LOAD_SCALES[load], minutes, advisors)


def test_meme_scenario_meme_strategie_meme_graine_memes_kpi():
    assert _run() == _run()


def test_une_autre_graine_donne_une_autre_journee():
    assert _run(seed=7)["arrived"] != _run(seed=8)["arrived"]


def test_les_kpi_sont_coherents():
    kpis = _run(load="SATURATED")
    assert kpis["arrived"] >= kpis["served"] > 0
    for rate in ("sla_rate", "sla_all_arrivals", "abandon_rate"):
        assert 0.0 <= kpis[rate] <= 1.0
    assert kpis["sla_all_arrivals"] <= kpis["sla_rate"]
    assert kpis["p95_wait_seconds"] >= kpis["avg_wait_seconds"] >= 0


def test_les_strategies_pilotent_vraiment_differemment():
    static = _run(strategy="STATIC_FIFO", load="SATURATED")
    threshold = _run(strategy="THRESHOLD", load="SATURATED")
    assert static["reassignments"] == 0
    assert threshold["reassignments"] > 0
    assert static["arrived"] == threshold["arrived"], "mêmes clients pour comparer les stratégies"


def test_evaluate_et_le_jumeau_partagent_le_meme_episode():
    rows = evaluate_models([], seeds=[7], minutes=60)
    medium_threshold = next(r for r in rows if r["load"] == "medium" and r["strategy"] == "THRESHOLD")
    expected = _run(strategy="THRESHOLD", seed=7, load="MEDIUM", minutes=60)
    assert medium_threshold["metrics"]["sla_rate"]["mean"] == pytest.approx(expected["sla_rate"])


def test_une_run_ppo_utilise_le_modele_par_defaut():
    pytest.importorskip("stable_baselines3")
    from app.workforce.policy import load_policy, model_path

    model = load_policy(str(model_path()))
    kpis = twin.run_episode("PPO", 7, twin.LOAD_SCALES["SATURATED"], 120, 3, model=model)

    assert kpis["arrived"] > 0
    assert kpis == twin.run_episode("PPO", 7, twin.LOAD_SCALES["SATURATED"], 120, 3, model=model)


def test_ppo_sans_modele_est_refuse():
    with pytest.raises(ValueError, match="PPO"):
        twin.run_episode("PPO", 7, 0.35, 60, 3)


@pytest.fixture
def client():
    app = FastAPI()
    app.include_router(router, prefix="/ai/workforce")
    return TestClient(app)


BODY = {"strategy": "THRESHOLD", "seed": 7, "load_profile": "MEDIUM", "duration_minutes": 120,
        "advisors_per_skill": 3}


def test_l_api_renvoie_les_kpi_de_la_run(client):
    response = client.post("/ai/workforce/twin-run", json=BODY)

    assert response.status_code == 200
    body = response.json()
    assert body == {**_run(), "strategy": "THRESHOLD", "seed": 7, "load_profile": "MEDIUM"}


@pytest.mark.parametrize("field,value", [
    ("strategy", "RANDOM"), ("load_profile", "EXTREME"), ("seed", -1),
    ("duration_minutes", 0), ("duration_minutes", 1441), ("advisors_per_skill", 0),
])
def test_l_api_refuse_un_scenario_invalide(client, field, value):
    assert client.post("/ai/workforce/twin-run", json={**BODY, field: value}).status_code == 422


def test_ppo_sans_modele_renvoie_503(client, monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "workforce_model_path", str(tmp_path / "absent.zip"))

    assert client.post("/ai/workforce/twin-run", json={**BODY, "strategy": "PPO"}).status_code == 503


def test_la_charge_du_scenario_change_vraiment_le_trafic():
    # Les échelles de l'évaluation PPO : un jumeau plus léger fausserait toute comparaison.
    assert twin.LOAD_SCALES == {"LOW": 0.2, "MEDIUM": 0.35, "SATURATED": 1.0}
    low, medium, saturated = (_run(load=load)["arrived"] for load in ("LOW", "MEDIUM", "SATURATED"))
    assert low < medium < saturated
