"""
Baselines — à valider AVANT le RL.
STATIC_FIFO : affectation statique par compétence, FIFO.
THRESHOLD : réaffecte si file > N.
TODO (Ines).
"""

from app.contracts.workforce import WorkforceState, WorkforceAction
from .actions import select_donor


def static_fifo(state: dict) -> dict:
    """
    Stratégie de référence la plus simple : ne fait AUCUNE réaffectation.
    Chaque conseiller reste sur sa compétence d'origine, premier arrivé
    premier servi au sein de chaque file.
    """
    return {
        "action": {"type": "NONE"},
        "reason": "STATIC_FIFO : aucune réaffectation, gestion figée par compétence.",
        "expected_gain": {},
    }


def threshold(state: dict, n: int = 10) -> dict:
    """
    Heuristique à seuil : si une file dépasse n clients en attente,
    réaffecte un conseiller disponible d'une autre compétence
    (celle qui a le moins de clients en attente) vers la file surchargée.
    """
    queues = state["queues"]          # ex: {"ACCOUNT": 12, "CARD": 3, "CREDIT": 1, "ADVISORY": 2}
    available = state["available"]    # ex: {"ACCOUNT": 0, "CARD": 3, "CREDIT": 2, "ADVISORY": 1}

    # 1. Trouver la file la PLUS chargée, celle qui dépasse le seuil n
    overloaded_skill = None
    max_queue_length = n  # on ne considère que les files STRICTEMENT au-dessus du seuil

    for skill, queue_length in queues.items():
        if queue_length > max_queue_length:
            overloaded_skill = skill
            max_queue_length = queue_length

    # 2. Si aucune file ne dépasse le seuil, on ne fait rien (comme FIFO)
    if overloaded_skill is None:
        return {
            "action": {"type": "NONE"},
            "reason": f"Aucune file ne dépasse le seuil de {n} clients.",
            "expected_gain": {},
        }

    # 3. Chercher, parmi les AUTRES compétences, celle qui a le plus de
    #    conseillers disponibles ET la file la plus courte (candidate au prêt)
    donor_skill = select_donor(queues, available, overloaded_skill)

    # 4. Si personne n'est disponible ailleurs, on ne peut rien faire
    if donor_skill is None:
        return {
            "action": {"type": "NONE"},
            "reason": f"File {overloaded_skill} surchargée, mais aucun conseiller disponible ailleurs.",
            "expected_gain": {},
        }

    # 5. On propose de réaffecter UN conseiller du donneur vers le receveur
    return {
        "action": {
            "type": "REASSIGN",
            "from_pool": donor_skill,
            "to_pool": overloaded_skill,
            "count": 1,
        },
        "reason": (
            f"File {overloaded_skill} à {max_queue_length} clients "
            f"(seuil={n}) — réaffectation depuis {donor_skill}."
        ),
        "expected_gain": {},
    }