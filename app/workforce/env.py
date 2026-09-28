
"""
Environnement Gymnasium — CallCenterEnv.
Simule le centre minute par minute : arrivées, files, conseillers, abandons.
"""

import random
import numpy as np
import gymnasium as gym
from gymnasium import spaces

from app.workforce.arrivals import generate_arrivals, InjectedEvent

SKILLS = ["ACCOUNT", "CARD", "CREDIT", "ADVISORY"]
STEP_MINUTES = 1.0          # durée d'un pas de simulation
SLA_TARGET_SECONDS = 60     # objectif : traité en moins de 60s
PATIENCE_DECAY = {          # combien de "patience" perdue par minute d'attente
    "CALME": 5,
    "IMPATIENT": 15,
    "INSATISFAIT": 10,
    "EXIGEANT": 12,
    "CHURN_RISK": 8,
}


class CallCenterEnv(gym.Env):
    def __init__(self, n_advisors_per_skill: int = 3, episode_minutes: float = 480):
        super().__init__()
        self.episode_minutes = episode_minutes
        self.n_advisors_per_skill = n_advisors_per_skill

        # 5 actions : NONE + une par compétence à renforcer
        self.action_space = spaces.Discrete(len(SKILLS) + 1)

        # Observation : 4 files + 4 attentes + 4 dispos + sla + heure + trend = 15 valeurs
        self.observation_space = spaces.Box(
            low=0.0, high=np.inf, shape=(15,), dtype=np.float32
        )

        self.advisors = []
        self.queues = {s: [] for s in SKILLS}
        self.current_time = 0.0
        self.arrivals_queue = []       # liste des arrivées futures, triée par temps
        self.stats = {"resolved": 0, "abandoned": 0, "sla_met": 0, "total": 0}

    # -----------------------------------------------------------------
    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        if seed is not None:
            random.seed(seed)

        # Recrée les conseillers : chacun compétent sur UNE seule compétence de base
        self.advisors = []
        advisor_id = 0
        for skill in SKILLS:
            for _ in range(self.n_advisors_per_skill):
                self.advisors.append({
                    "id": advisor_id,
                    "home_skill": skill,       # sa compétence normale
                    "current_skill": skill,    # sa compétence actuelle (peut changer via REASSIGN)
                    "busy_until": None,
                })
                advisor_id += 1

        self.queues = {s: [] for s in SKILLS}
        self.current_time = 0.0
        self.stats = {"resolved": 0, "abandoned": 0, "sla_met": 0, "total": 0}

        # Génère TOUTES les arrivées de l'épisode à l'avance
        self.arrivals_queue = generate_arrivals(
            duration_minutes=self.episode_minutes, start_hour=8, rng_seed=seed
        )

        observation = self._get_observation()
        info = {}
        return observation, info

    # -----------------------------------------------------------------
    def step(self, action):
        self._apply_action(action)

        window_end = self.current_time + STEP_MINUTES
        self._admit_new_arrivals(window_end)
        self._assign_advisors()
        self._free_finished_advisors(window_end)
        n_abandoned = self._apply_patience_decay()

        self.current_time = window_end
        reward = self._compute_reward(n_abandoned)
        observation = self._get_observation()

        terminated = self.current_time >= self.episode_minutes
        truncated = False
        info = {"stats": dict(self.stats)}

        return observation, reward, terminated, truncated, info

    # -----------------------------------------------------------------
    def _apply_action(self, action):
        """Action 0 = rien. Action k = renforcer SKILLS[k-1]."""
        if action == 0:
            return
        target_skill = SKILLS[action - 1]

        # Cherche un conseiller LIBRE dont la compétence actuelle n'est pas déjà la cible
        candidates = [
            a for a in self.advisors
            if a["busy_until"] is None and a["current_skill"] != target_skill
        ]
        if not candidates:
            return  # personne à déplacer, action sans effet

        chosen = candidates[0]
        chosen["current_skill"] = target_skill

    # -----------------------------------------------------------------
    def _admit_new_arrivals(self, window_end):
        """Fait entrer dans les files tous les clients arrivés avant window_end."""
        while self.arrivals_queue and self.arrivals_queue[0]["arrival_time"] < window_end:
            client = self.arrivals_queue.pop(0)
            client["patience"] = 100.0
            self.queues[client["skill_needed"]].append(client)

    # -----------------------------------------------------------------
    def _assign_advisors(self):
        """Assigne les conseillers libres aux clients en attente (FIFO)."""
        for skill in SKILLS:
            queue = self.queues[skill]
            free_advisors = [
                a for a in self.advisors
                if a["busy_until"] is None and a["current_skill"] == skill
            ]
            while queue and free_advisors:
                client = queue.pop(0)
                advisor = free_advisors.pop(0)
                duration = random.expovariate(1 / 3.0)   # ~3 min moyenne
                advisor["busy_until"] = self.current_time + duration

                wait_time = self.current_time - client["arrival_time"]
                self.stats["total"] += 1
                self.stats["resolved"] += 1
                if wait_time * 60 <= SLA_TARGET_SECONDS:
                    self.stats["sla_met"] += 1

    # -----------------------------------------------------------------
    def _free_finished_advisors(self, window_end):
        """Libère les conseillers dont l'appel en cours est terminé."""
        for advisor in self.advisors:
            if advisor["busy_until"] is not None and advisor["busy_until"] <= window_end:
                advisor["busy_until"] = None
                advisor["current_skill"] = advisor["home_skill"]  # revient à sa base

    # -----------------------------------------------------------------
    def _apply_patience_decay(self):
        """Fait décroître la patience ; retire les clients qui abandonnent."""
        n_abandoned = 0
        for skill in SKILLS:
            remaining = []
            for client in self.queues[skill]:
                decay = PATIENCE_DECAY[client["profile"]] * STEP_MINUTES
                client["patience"] -= decay
                if client["patience"] <= 0:
                    n_abandoned += 1
                    self.stats["abandoned"] += 1
                    self.stats["total"] += 1
                else:
                    remaining.append(client)
            self.queues[skill] = remaining
        return n_abandoned

    # -----------------------------------------------------------------
    def _compute_reward(self, n_abandoned):
        """Récompense pondérée : SLA bon = +, attente/abandons = -."""
        total_waiting = sum(len(q) for q in self.queues.values())
        sla_ratio = (
            self.stats["sla_met"] / self.stats["resolved"]
            if self.stats["resolved"] > 0 else 1.0
        )
        reward = (
            0.4 * sla_ratio
            - 0.25 * (total_waiting / 50.0)     # normalisé, files longues pénalisées
            - 0.35 * n_abandoned                 # abandon = lourdement pénalisé
        )
        return reward

    # -----------------------------------------------------------------
    def _get_observation(self):
        queue_lengths = [len(self.queues[s]) for s in SKILLS]
        avg_waits = []
        for s in SKILLS:
            if self.queues[s]:
                waits = [self.current_time - c["arrival_time"] for c in self.queues[s]]
                avg_waits.append(sum(waits) / len(waits))
            else:
                avg_waits.append(0.0)
        availability = [
            sum(1 for a in self.advisors if a["busy_until"] is None and a["current_skill"] == s)
            for s in SKILLS
        ]
        sla_ratio = (
            self.stats["sla_met"] / self.stats["resolved"]
            if self.stats["resolved"] > 0 else 1.0
        )
        hour = 8 + (self.current_time // 60)
        trend = 1.0  # simplifié pour l'instant

        obs = queue_lengths + avg_waits + availability + [sla_ratio, hour, trend]
        return np.array(obs, dtype=np.float32)