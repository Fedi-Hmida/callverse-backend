"""
Outils exposés au Customer Advisor (specs + implémentations).

search_knowledge_base est géré à part dans app/advisor/kb/retriever.py
(recherche vectorielle locale, pas un appel backend).
"""
from __future__ import annotations

from typing import Any

from app.clients.backend_client import backend_client

TOOL_SPECS: list[dict[str, Any]] = [
    {
        "name": "get_customer",
        "description": "Récupère la fiche client (comptes, cartes, segment et ancienneté).",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_transactions",
        "description": "Récupère les transactions récentes du client.",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "check_system_status",
        "description": "Vérifie s'il y a un incident système connu sur une zone donnée (ex: panne carte/paiement).",
        "input_schema": {
            "type": "object",
            "properties": {"zone": {"type": "string", "description": "Zone ou service concerné, ex: 'paiement_carte', 'app_mobile'."}},
            "required": ["zone"],
        },
    },
    {
        "name": "search_knowledge_base",
        "description": "Recherche sémantique dans la base de connaissances (articles KB).",
        "input_schema": {
            "type": "object",
            "properties": {"query": {"type": "string"}, "top_k": {"type": "integer"}},
            "required": ["query"],
        },
    },
    {
        "name": "create_case",
        "description": "Crée un ticket rattaché à la conversation en cours. Choisis sa catégorie selon l'intention suivie.",
        "input_schema": {
            "type": "object",
            "properties": {
                "subject": {"type": "string"},
                "incident_id": {"type": "string"},
                "category": {
                    "type": "string",
                    "enum": ["BALANCE", "CARD", "CREDIT", "FRAUD", "ACCOUNT_CLOSURE", "ACCOUNTS", "CARDS", "OTHER"],
                    "description": "Intention métier du ticket ; utilise OTHER si elle est inconnue.",
                },
            },
            "required": ["subject"],
        },
    },
    {
        "name": "apply_credit",
        "description": "Applique un geste commercial. Refusé côté backend si hors plafond.",
        "input_schema": {
            "type": "object",
            "properties": {"amount": {"type": "number"}},
            "required": ["amount"],
        },
    },
]
# customer_id et conversation_id ne figurent dans aucun schéma : nodes.TRUSTED_IDENTIFIERS
# les injecte depuis la requête du backend, le LLM ne peut pas les choisir.
# Pas d'outil d'escalade : l'escalade est l'action ESCALATE de la décision finale, que le
# backend transforme en vraie escalade après avoir publié la réponse (un seul chemin).

TOOL_IMPLEMENTATIONS = {
    "get_customer": backend_client.get_customer,
    "get_transactions": backend_client.get_transactions,
    "check_system_status": backend_client.check_system_status,
    "create_case": backend_client.create_case,
    "apply_credit": backend_client.apply_credit,
    # search_knowledge_base volontairement absent : géré via app/advisor/kb/retriever.py
}
