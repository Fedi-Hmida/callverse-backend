"""
Charge le modèle entraîné, expose decide(state) -> WorkforceAction.
Le routeur n'importe jamais Gymnasium/Stable-Baselines3 directement, seulement ce module.
TODO (Ines).
"""
from app.contracts.workforce import WorkforceState, WorkforceAction


def decide(state: WorkforceState) -> WorkforceAction:
    raise NotImplementedError
