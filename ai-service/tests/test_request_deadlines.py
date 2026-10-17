"""
Le backend abandonne un appel au bout de 30 s. L'ai-service doit donc répondre avant :
une échéance globale par requête renvoie 504 plutôt que de continuer à travailler
(et à appeler des outils) pour une réponse que personne n'attend plus.
"""
import asyncio
import time

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.advisor import router as advisor_router
from app.config import settings
from app.simulator import router as simulator_router

ADVISOR_BODY = {
    "conversation_id": "11111111-1111-1111-1111-111111111111",
    "customer_id": "22222222-2222-2222-2222-222222222222",
    "message": "Ma carte est bloquée",
    "history": [],
    "context": {"tenure_months": 12},
    "mode": "LIVE",
}
SIMULATOR_BODY = {
    "conversation_id": "11111111-1111-1111-1111-111111111111",
    "history": [],
    "last_advisor_reply": None,
    "wait_seconds": 0,
    "state": {"profile": "calme", "patience": 50, "satisfaction": 50, "objective": "x", "objective_met": False},
}


def _client(router, prefix: str) -> TestClient:
    app = FastAPI()
    app.include_router(router.router, prefix=prefix)
    return TestClient(app)


def test_l_echeance_par_defaut_reste_sous_le_delai_du_backend():
    assert 0 < settings.ai_request_deadline_seconds < 30


def test_l_advisor_trop_lent_renvoie_504_avant_l_echeance(monkeypatch):
    async def slow(_request):
        await asyncio.sleep(5)

    monkeypatch.setattr(advisor_router, "run_advisor", slow)
    monkeypatch.setattr(settings, "ai_request_deadline_seconds", 0.2)

    started = time.monotonic()
    response = _client(advisor_router, "/ai/advisor").post("/ai/advisor/respond", json=ADVISOR_BODY)

    assert response.status_code == 504
    assert time.monotonic() - started < 2


def test_une_panne_de_l_advisor_ne_divulgue_pas_le_detail_de_l_exception(monkeypatch):
    async def broken(_request):
        raise RuntimeError("postgresql://user:secret@host/db refused")

    monkeypatch.setattr(advisor_router, "run_advisor", broken)

    response = _client(advisor_router, "/ai/advisor").post("/ai/advisor/respond", json=ADVISOR_BODY)

    assert response.status_code == 503
    assert "secret" not in response.text


def _graph_returning(status: str, messages: list) -> object:
    class FixedGraph:
        def invoke(self, state):
            return {**state, "status": status, "messages": messages}

    return FixedGraph()


def test_un_statut_final_n_a_pas_de_contenu_meme_au_premier_tour(monkeypatch):
    monkeypatch.setattr(simulator_router, "_graph", _graph_returning("abandonne", []))

    response = _client(simulator_router, "/ai/simulator").post("/ai/simulator/next-message", json=SIMULATOR_BODY)

    assert response.status_code == 200
    assert response.json()["status"] == "abandonne"
    assert response.json()["content"] is None


def test_un_statut_final_ignore_le_dernier_message_client(monkeypatch):
    monkeypatch.setattr(
        simulator_router, "_graph", _graph_returning("resolu", [{"role": "customer", "content": "Merci"}])
    )

    response = _client(simulator_router, "/ai/simulator").post("/ai/simulator/next-message", json=SIMULATOR_BODY)

    assert response.json()["content"] is None


def test_le_simulateur_trop_lent_renvoie_504_avant_l_echeance(monkeypatch):
    class SlowGraph:
        def invoke(self, _state):
            time.sleep(3)

    monkeypatch.setattr(simulator_router, "_graph", SlowGraph())
    monkeypatch.setattr(settings, "ai_request_deadline_seconds", 0.2)

    # Client persistant, comme un vrai serveur : sans lui, la fermeture de la boucle après la
    # requête attend le thread du graphe et fausse la mesure.
    with _client(simulator_router, "/ai/simulator") as client:
        started = time.monotonic()
        response = client.post("/ai/simulator/next-message", json=SIMULATOR_BODY)
        elapsed = time.monotonic() - started

    assert response.status_code == 504
    assert elapsed < 2


def test_le_simulateur_tourne_dans_son_propre_pool_borne(monkeypatch):
    # Sinon des simulations lentes occupent le pool par défaut, partagé avec les embeddings
    # de l'Advisor, et font échouer l'Advisor sur son échéance.
    import threading

    seen = {}

    class RecordingGraph:
        def invoke(self, state):
            seen["thread"] = threading.current_thread().name
            return {**state, "status": "abandonne", "messages": []}

    monkeypatch.setattr(simulator_router, "_graph", RecordingGraph())

    _client(simulator_router, "/ai/simulator").post("/ai/simulator/next-message", json=SIMULATOR_BODY)

    assert seen["thread"].startswith("simulator")
    assert simulator_router._EXECUTOR._max_workers <= 4
