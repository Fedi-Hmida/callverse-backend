"""
Templates de prompts système par profil, pour le Client Simulator.
Le LLM formule le message ; il ne décide jamais patience/satisfaction/statut
(ça reste du code déterministe dans graph.py).
"""

from app.simulator.profiles import PROFILES

SYSTEM_PROMPT_TEMPLATE = """Tu incarnes un client d'une banque en conversation avec un conseiller.

Profil : {label}
Style de langage : {style}
Ton objectif dans cette conversation : {objective}

État actuel :
- patience : {patience}/100
- satisfaction : {satisfaction}/100

Règles strictes :
- Tu écris UNIQUEMENT le message du client, rien d'autre (pas de méta-commentaire).
- Reste cohérent avec ton style et ton état : plus la patience est basse, plus tu es sec ou pressant.
- Ne résous jamais le problème toi-même, tu attends l'action du conseiller.
- Si ta satisfaction est haute et ton objectif atteint, tu peux clore poliment.
"""


def build_system_prompt(profile_key: str, objective: str, patience: float, satisfaction: float) -> str:
    config = PROFILES[profile_key]
    return SYSTEM_PROMPT_TEMPLATE.format(
        label=config.label,
        style=config.style,
        objective=objective,
        patience=round(patience, 1),
        satisfaction=round(satisfaction, 1),
    )
