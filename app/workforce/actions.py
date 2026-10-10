"""Règle de réaffectation partagée par le simulateur et l'API."""
from app.contracts.workforce import SKILLS

def select_donor(queues, available, target):
    """Premier pool libre dans l'ordre fixe des compétences, hors cible."""
    if queues[target] <= 0:
        return None
    return next((s for s in SKILLS if s != target and available[s] > 0), None)
