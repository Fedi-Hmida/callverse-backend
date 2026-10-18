"""
Client HTTP mutualisé vers le backend Spring Boot.

Toute la couche AI passe par ici pour parler au backend (source de vérité
métier) : aucun autre module ne doit instancier httpx.AsyncClient lui-même.
"""
from __future__ import annotations

from typing import Any

import httpx

from app.config import settings

_BANKING_SERVICES = {
    "CARD_PAYMENTS",
    "ONLINE_BANKING",
    "MOBILE_APP",
    "ATM_NETWORK",
    "TRANSFERS",
}

_SERVICE_ALIASES = {
    "paiement_carte": "CARD_PAYMENTS",
    "paiement carte": "CARD_PAYMENTS",
    "app_mobile": "MOBILE_APP",
    "application mobile": "MOBILE_APP",
    "banque_en_ligne": "ONLINE_BANKING",
    "banque en ligne": "ONLINE_BANKING",
    "reseau_dab": "ATM_NETWORK",
    "réseau dab": "ATM_NETWORK",
    "virement": "TRANSFERS",
}

_TICKET_CATEGORIES = {
    "BALANCE",
    "CARD",
    "CREDIT",
    "FRAUD",
    "ACCOUNT_CLOSURE",
    "ACCOUNTS",
    "CARDS",
    "OTHER",
}


class BackendClient:
    """HTTP client for the backend's service-key-protected internal tools."""

    def __init__(
        self,
        base_url: str | None = None,
        timeout: float = 5.0,
        service_key: str | None = None,
    ) -> None:
        self._base_url = base_url or settings.backend_base_url
        self._timeout = timeout
        self._service_key = settings.internal_service_key if service_key is None else service_key
        self._client: httpx.AsyncClient | None = None

    def _get_client(self) -> httpx.AsyncClient:
        if not self._service_key:
            raise RuntimeError("INTERNAL_SERVICE_KEY must be configured before calling backend tools")
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._base_url,
                timeout=self._timeout,
                limits=httpx.Limits(max_keepalive_connections=20, max_connections=50),
                headers={"X-Internal-Key": self._service_key},
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
        return await self._get(f"/internal/customers/{customer_id}")

    async def get_transactions(self, customer_id: str, n: int = 3) -> dict[str, Any]:
        return await self._get(f"/internal/customers/{customer_id}/transactions", params={"n": n})

    async def check_system_status(self, zone: str) -> dict[str, Any]:
        normalized_zone = zone.strip().casefold()
        service = _SERVICE_ALIASES.get(normalized_zone)
        if service is None:
            candidate = zone.strip().upper()
            service = candidate if candidate in _BANKING_SERVICES else None
        if service is None:
            raise ValueError(f"UNRECOGNIZED_SERVICE: {zone}")
        return await self._get("/internal/service-incidents", params={"service": service})

    async def create_case(
        self,
        customer_id: str,
        subject: str,
        conversation_id: str,
        incident_id: str | None = None,
        category: str | None = None,
    ) -> dict[str, Any]:
        ticket_category = "OTHER" if category is None else category.upper()
        if ticket_category not in _TICKET_CATEGORIES:
            raise ValueError(f"Unsupported ticket category: {category}")

        payload: dict[str, Any] = {
            "customerId": customer_id,
            "conversationId": conversation_id,
            "category": ticket_category,
            "title": subject,
        }
        if incident_id:
            payload["description"] = f"Incident lié : {incident_id}"
        return await self._post("/internal/tickets", payload)

    async def apply_credit(self, customer_id: str, amount: float, conversation_id: str,
                           request_id: str, reason: str = "Geste commercial demandé pendant la conversation") -> dict[str, Any]:
        client = self._get_client()
        response = await client.post(
            "/internal/credits",
            json={"customerId": customer_id, "conversationId": conversation_id,
                  "requestId": request_id, "amount": amount, "reason": reason},
        )
        if response.status_code == 501:
            return {"code": "NOT_IMPLEMENTED", "available": False}
        if response.status_code == 409 and response.json().get("code") == "CREDIT_LIMIT_EXCEEDED":
            return {"code": "CREDIT_LIMIT_EXCEEDED", "applied": False, "requires_approval": True}
        response.raise_for_status()
        return response.json()

    async def escalate(self, conversation_id: str, reason: str) -> dict[str, Any]:
        return await self._post(
            f"/internal/conversations/{conversation_id}/escalate",
            {"reason": reason},
        )


backend_client = BackendClient()
