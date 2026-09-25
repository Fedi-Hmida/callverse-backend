"""
Teste le routeur et le graphe SANS appel LLM reel :
la methode invoke() du LLM est remplacee par une reponse fixe.
Utile pendant que le quota Groq est epuise.
"""
from unittest.mock import patch, MagicMock

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def _fake_invoke(self, *args, **kwargs):
    fake_response = MagicMock()
    fake_response.content = "Message client simule (mock, sans appel LLM reel)."
    return fake_response


@patch("langchain_groq.ChatGroq.invoke", _fake_invoke)
def test_next_message_endpoint_conforme_au_contrat():
    payload = {
        "conversation_id": "11111111-1111-1111-1111-111111111111",
        "state": {
            "profile": "churn",
            "patience": 80,
            "satisfaction": 50,
            "objective": "faire opposition sur une carte suite a une transaction suspecte",
        },
        "history": [
            {"role": "customer", "content": "Bonjour, transaction non reconnue.", "ts": "2026-09-19T10:00:00Z"},
            {"role": "advisor", "content": "Je bloque votre carte a l'instant, 340 euros contestes, dossier ouvert.", "ts": "2026-09-19T10:00:30Z"},
        ],
    }

    response = client.post("/ai/simulator/next-message", json=payload)

    print("Status:", response.status_code)
    print("Body:", response.json())

    assert response.status_code == 200
    body = response.json()
    assert "status" in body
    assert "state" in body
