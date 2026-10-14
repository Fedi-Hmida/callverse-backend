"""
Client HTTP mutualisé vers le backend Spring Boot.

Toute la couche AI passe par ici pour parler au backend (source de vérité
métier) : aucun autre module ne doit instancier httpx.AsyncClient lui-même.
"""
from __future__ import annotations

from typing import Any

import httpx

from app.config import settings


class BackendClient:
    """
    Sept outils metier, alignes sur les endpoints figes du document 01
    (Contrat 2 - Service AI vers Backend), prefixe /api/v1/internal.
    """

    def __init__(self, base_url: str | None = None, timeout: float = 5.0) -> None:
        self._base_url = base_url or settings.backend_base_url
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._base_url,
                timeout=self._timeout,
                limits=httpx.Limits(max_keepalive_connections=20, max_connections=50),
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        client = self._get_client()
        resp = await client.get(path, params=params)
        resp.raise_for_status()
        return resp.json()

    async def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        client = self._get_client()
        resp = await client.post(path, json=payload)
        resp.raise_for_status()
        return resp.json()

    async def get_customer(self, customer_id: str) -> dict[str, Any]:
        return await self._get(f"/api/v1/internal/customers/{customer_id}")

    async def get_transactions(self, customer_id: str, n: int = 3) -> dict[str, Any]:
        return await self._get(f"/api/v1/internal/customers/{customer_id}/invoices", params={"n": n})

    async def check_system_status(self, zone: str) -> dict[str, Any]:
        return await self._get("/api/v1/internal/network/status", params={"zone": zone})

    async def search_knowledge_base(self, query: str, k: int = 5) -> dict[str, Any]:
        return await self._get("/api/v1/internal/kb/search", params={"q": query, "k": k})

    async def create_case(
        self, customer_id: str, subject: str, incident_id: str | None = None
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"customer_id": customer_id, "subject": subject}
        if incident_id:
            payload["incident_id"] = incident_id
        return await self._post("/api/v1/internal/tickets", payload)

    async def apply_credit(self, customer_id: str, amount: float) -> dict[str, Any]:
        return await self._post("/api/v1/internal/credits", {"customer_id": customer_id, "amount": amount})

    async def escalate(self, conversation_id: str, reason: str) -> dict[str, Any]:
        return await self._post(
            f"/api/v1/internal/conversations/{conversation_id}/escalate",
            {"reason": reason},
        )


backend_client = BackendClient()
