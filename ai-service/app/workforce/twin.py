"""
Runs du jumeau numérique : un épisode de CallCenterEnv, pour une charge, une durée, un effectif
et une stratégie de pilotage. Même scénario, même stratégie, même graine : mêmes KPI.

Un seul épisode pour tout le monde : l'API (/ai/workforce/twin-run) et la comparaison hors ligne
(evaluate.py) appellent run_episode, donc leurs chiffres ne peuvent pas diverger.
"""
from app.workforce.baselines import threshold
from app.workforce.env import CallCenterEnv

# Les échelles d'arrivée de l'évaluation PPO (evaluate.py) : faible, moyenne, saturée.
LOAD_SCALES = {"LOW": 0.2, "MEDIUM": 0.35, "SATURATED": 1.0}
# Seuil de la stratégie THRESHOLD dans l'évaluation : renforcer une file à partir de 3 clients.
THRESHOLD_N = 3


def run_episode(strategy: str, seed: int, arrival_scale: float, episode_minutes: float,
                advisors_per_skill: int, model=None) -> dict:
    """KPI d'un épisode ; `model` est la politique PPO (chargée par l'appelant pour PPO)."""
    if strategy == "PPO" and model is None:
        raise ValueError("La stratégie PPO demande un modèle.")
    env = CallCenterEnv(n_advisors_per_skill=advisors_per_skill, episode_minutes=episode_minutes,
                        arrival_scale=arrival_scale)
    try:
        obs, _ = env.reset(seed=seed)
        done = False
        info = {}
        while not done:
            obs, _, terminated, truncated, info = env.step(_action(strategy, env, obs, model))
            done = terminated or truncated
        return {**info["kpis"], "arrived": env.arrived_count}
    finally:
        env.close()


def _action(strategy: str, env: CallCenterEnv, obs, model) -> int:
    if strategy == "PPO":
        return int(model.predict(obs, deterministic=True)[0])
    if strategy == "THRESHOLD":
        result = threshold(env.api_state(), n=THRESHOLD_N)["action"]
        return list(env.queues).index(result["to_pool"]) + 1 if result["type"] == "REASSIGN" else 0
    return 0  # STATIC_FIFO : aucun renfort
