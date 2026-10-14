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
        "description": "Récupère la fiche client (contrat, forfait, historique, risque de churn).",
        "input_schema": {
            "type": "object",
            "properties": {"customer_id": {"type": "string"}},
            "required": ["customer_id"],
        },
    },
    {
        "name": "get_transactions",
        "description": "Récupère les transactions récentes du client.",
        "input_schema": {
            "type": "object",
            "properties": {"customer_id": {"type": "string"}},
            "required": ["customer_id"],
        },
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
        "description": "Crée un ticket de suivi rattaché à la conversation ou à un incident.",
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "subject": {"type": "string"},
                "incident_id": {"type": "string"},
            },
            "required": ["customer_id", "subject"],
        },
    },
    {
        "name": "apply_credit",
        "description": "Applique un geste commercial. Refusé côté backend si hors plafond.",
        "input_schema": {
            "type": "object",
            "properties": {"customer_id": {"type": "string"}, "amount": {"type": "number"}},
            "required": ["customer_id", "amount"],
        },
    },
    {
        "name": "escalate",
        "description": "Remonte la demande au superviseur.",
        "input_schema": {
            "type": "object",
            "properties": {
                "conversation_id": {"type": "string"},
                "reason": {"type": "string"},
            },
            "required": ["conversation_id", "reason"],
        },
    },
]

TOOL_IMPLEMENTATIONS = {
    "get_customer": backend_client.get_customer,
    "get_transactions": backend_client.get_transactions,
    "check_system_status": backend_client.check_system_status,
    "create_case": backend_client.create_case,
    "apply_credit": backend_client.apply_credit,
    "escalate": backend_client.escalate,
    # search_knowledge_base volontairement absent : géré via app/advisor/kb/retriever.py
}
