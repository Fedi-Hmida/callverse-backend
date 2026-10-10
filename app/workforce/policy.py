"""Inférence PPO hors entraînement et adaptation en proposition métier."""
from functools import lru_cache
from pathlib import Path
from threading import Lock
import numpy as np
from app.config import settings
from app.contracts.workforce import SKILLS, Strategy, WorkforceState, WorkforceAction
from .baselines import threshold
from .actions import select_donor

class PolicyUnavailable(RuntimeError):
    pass

_prediction_lock = Lock()

def state_to_observation(state: WorkforceState) -> np.ndarray:
    """Même ordre et mêmes unités que CallCenterEnv._get_observation."""
    values = ([state.queues[s] for s in SKILLS]
              + [state.avg_wait[s] / 60.0 for s in SKILLS]
              + [state.available[s] for s in SKILLS]
              + [state.sla_today, state.hour, state.trend])
    obs = np.asarray(values, dtype=np.float32)
    if not np.isfinite(obs).all():
        raise ValueError("Les valeurs dépassent la plage numérique du modèle.")
    return obs

@lru_cache(maxsize=1)
def load_policy(path: str):
    # Import coûteux seulement lorsque PPO est demandé.
    from stable_baselines3 import PPO
    try:
        model = PPO.load(path, device="cpu")
        if model.observation_space.shape != (15,) or getattr(model.action_space, "n", None) != 5:
            raise ValueError("Espaces du modèle incompatibles.")
        return model
    except Exception as exc:
        raise PolicyUnavailable("Modèle PPO absent, illisible ou incompatible.") from exc

def model_path() -> Path:
    path = Path(settings.workforce_model_path)
    return path if path.is_absolute() else Path(__file__).resolve().parents[2] / path

def decide(state: WorkforceState, strategy: Strategy = "PPO",
           threshold_n: int = 10) -> WorkforceAction:
    if strategy == "STATIC_FIFO":
        return WorkforceAction(type="NONE", strategy=strategy,
                               reason="STATIC_FIFO : maintien de l'affectation actuelle.")
    if strategy == "THRESHOLD":
        result = threshold(state.model_dump(), n=threshold_n)
        action = result["action"]
        action_id = SKILLS.index(action["to_pool"]) + 1 if action["type"] == "REASSIGN" else 0
        return WorkforceAction(**action, action_id=action_id, strategy=strategy,
                               reason=result["reason"], expected_gain=result["expected_gain"])
    path = model_path()
    if not path.is_file():
        raise PolicyUnavailable("Modèle PPO introuvable. Configurer WORKFORCE_MODEL_PATH.")
    model = load_policy(str(path))
    observation = state_to_observation(state)
    try:
        with _prediction_lock:
            predicted, _ = model.predict(observation, deterministic=True)
        action_id = int(predicted)
        if not 0 <= action_id <= 4:
            raise ValueError("Action inconnue.")
    except Exception as exc:
        raise PolicyUnavailable("Échec de l'inférence PPO.") from exc
    if action_id == 0:
        return WorkforceAction(type="NONE", reason="La policy PPO propose de maintenir l'affectation.")
    target = SKILLS[action_id - 1]
    donor = select_donor(state.queues, state.available, target)
    if donor is None:
        return WorkforceAction(type="NONE",
                               reason=f"PPO proposes {target}, but no feasible reassignment exists.")
    return WorkforceAction(type="REASSIGN", from_pool=donor, to_pool=target,
                           count=1, action_id=action_id,
                           reason=f"PPO propose de renforcer {target} ({state.queues[target]} clients, "
                                  f"{state.avg_wait[target]:.0f} s d'attente) depuis {donor}. "
                                  "Le pool donneur est choisi parmi les pools disponibles "
                                  "dans le meme ordre fixe que le simulateur.")
