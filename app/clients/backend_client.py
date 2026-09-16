"""
Client HTTP vers le backend Spring Boot (outils métier : get_customer,
apply_credit, escalate, etc.). Aucune règle métier n'est réimplémentée ici,
seulement des appels REST.
"""
import httpx

from app.config import settings


class BackendClient:
    def __init__(self, base_url: str = settings.backend_base_url):
        self.base_url = base_url
        self._client = httpx.Client(base_url=base_url)

    def get(self, path: str, **kwargs):
        raise NotImplementedError

    def post(self, path: str, **kwargs):
        raise NotImplementedError
