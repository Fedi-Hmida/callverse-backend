"""
Les 7 outils métier, mockés localement en attendant le backend réel.
get_customer, get_invoices, check_network_status, search_knowledge_base,
create_ticket, apply_credit, escalate.
Règle absolue : ne jamais réimplémenter une règle métier ici
(ex. plafond de geste commercial) — le backend est l'autorité.
"""


def get_customer(customer_id: str) -> dict:
    raise NotImplementedError


def get_invoices(customer_id: str, n: int) -> list[dict]:
    raise NotImplementedError


def check_network_status(zone: str) -> dict:
    raise NotImplementedError


def search_knowledge_base(query: str) -> list[dict]:
    raise NotImplementedError


def create_ticket(**kwargs) -> dict:
    raise NotImplementedError


def apply_credit(customer_id: str, montant: float) -> dict:
    raise NotImplementedError


def escalate(raison: str) -> dict:
    raise NotImplementedError
