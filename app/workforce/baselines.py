"""
Baselines — à valider AVANT le RL.
STATIC_FIFO : affectation statique par compétence, FIFO.
THRESHOLD : réaffecte si file > N.
TODO (Ines).
"""


def static_fifo(state: dict) -> dict:
    raise NotImplementedError


def threshold(state: dict, n: int = 10) -> dict:
    raise NotImplementedError
