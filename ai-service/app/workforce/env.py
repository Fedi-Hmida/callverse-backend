"""
Environnement Gymnasium — CallCenterEnv.

Simule le centre minute par minute :
- arrivées de clients
- files d'attente
- conseillers
- réaffectation temporaire des conseillers
- abandons
- respect du SLA
"""

import random
import numpy as np
import gymnasium as gym
from gymnasium import spaces

from app.workforce.arrivals import generate_arrivals
from app.workforce.actions import select_donor


# ---------------------------------------------------------------------
# Paramètres métier
# ---------------------------------------------------------------------

SKILLS = ["ACCOUNT", "CARD", "CREDIT", "ADVISORY"]

STEP_MINUTES = 1.0

SLA_TARGET_SECONDS = 60

PATIENCE_DECAY = {
    "CALME": 5,
    "IMPATIENT": 15,
    "INSATISFAIT": 10,
    "EXIGEANT": 12,
    "CHURN_RISK": 8,
}


class CallCenterEnv(gym.Env):
    """
    Environnement de simulation du centre de contacts.

    Actions :
        0 = aucune réaffectation
        1 = renforcer ACCOUNT
        2 = renforcer CARD
        3 = renforcer CREDIT
        4 = renforcer ADVISORY

    Observation :
        4 files
        + 4 temps d'attente moyens
        + 4 disponibilités
        + SLA
        + heure
        + tendance
        = 15 valeurs
    """

    metadata = {"render_modes": []}

    def __init__(
        self,
        n_advisors_per_skill: int = 3,
        episode_minutes: float = 480,
        arrival_scale: float = 1.0,
        training_scales: tuple | None = None,
    ):
        super().__init__()

        if episode_minutes <= 0 or n_advisors_per_skill < 1 or arrival_scale <= 0:
            raise ValueError("Invalid environment parameters")
        self.arrival_scale = arrival_scale
        self.training_scales = training_scales
        self.episode_minutes = episode_minutes
        self.n_advisors_per_skill = n_advisors_per_skill

        # -------------------------------------------------------------
        # Espace des actions
        # -------------------------------------------------------------
        self.action_space = spaces.Discrete(len(SKILLS) + 1)

        # 0 = rien
        # 1 = ACCOUNT
        # 2 = CARD
        # 3 = CREDIT
        # 4 = ADVISORY

        # -------------------------------------------------------------
        # Espace des observations
        # -------------------------------------------------------------
        self.observation_space = spaces.Box(
            low=0.0,
            high=np.inf,
            shape=(15,),
            dtype=np.float32,
        )

        self.advisors = []
        self.queues = {skill: [] for skill in SKILLS}

        self.current_time = 0.0
        self.arrivals_queue = []

        self.stats = {
            "resolved": 0,
            "abandoned": 0,
            "sla_met": 0,
            "total": 0,
        }

        # Générateur aléatoire propre à l'environnement
        self.rng = random.Random()

    # -----------------------------------------------------------------
    # RESET
    # -----------------------------------------------------------------

    def reset(self, seed=None, options=None):
        """
        Réinitialise complètement une simulation.
        """

        super().reset(seed=seed)

        # Générateur aléatoire reproductible
        episode_seed = seed if seed is not None else int(self.np_random.integers(0, 2**31))
        self.rng = random.Random(episode_seed)
        scale = self.rng.choice(self.training_scales) if self.training_scales else self.arrival_scale
        self.wait_seconds = []
        self.reassignments = 0
        self._last_served = self._last_sla = 0
        self._step_before = dict(self.stats)

        # -------------------------------------------------------------
        # Création des conseillers
        # -------------------------------------------------------------

        self.advisors = []

        advisor_id = 0

        for skill in SKILLS:
            for _ in range(self.n_advisors_per_skill):

                self.advisors.append(
                    {
                        "id": advisor_id,
                        "home_skill": skill,
                        "current_skill": skill,
                        "busy_until": None,
                    }
                )

                advisor_id += 1

        # -------------------------------------------------------------
        # Réinitialisation
        # -------------------------------------------------------------

        self.queues = {
            skill: []
            for skill in SKILLS
        }

        self.current_time = 0.0

        self.stats = {
            "resolved": 0,
            "abandoned": 0,
            "sla_met": 0,
            "total": 0,
        }

        # -------------------------------------------------------------
        # Génération des arrivées
        # -------------------------------------------------------------

        self.arrivals_queue = generate_arrivals(
            duration_minutes=self.episode_minutes,
            arrival_scale=scale,
            start_hour=8,
            rng_seed=episode_seed,
        )

        for client in self.arrivals_queue:
            client["service_duration"] = self.rng.expovariate(1 / 3.0)
        self.arrived_count = len(self.arrivals_queue)
        self._done = False
        observation = self._get_observation()

        info = {}

        return observation, info

    # -----------------------------------------------------------------
    # STEP
    # -----------------------------------------------------------------

    def step(self, action):
        if self._done:
            raise RuntimeError("Episode finished: call reset().")
        if not self.action_space.contains(action):
            raise ValueError(f"Invalid action: {action}")
        action = int(action)
        self._step_before = dict(self.stats)
        self._free_finished_advisors(self.current_time)
        self._admit_new_arrivals(self.current_time)
        self._apply_action(action)
        self._assign_advisors()
        self.current_time = min(self.current_time + STEP_MINUTES, self.episode_minutes)
        self._free_finished_advisors(self.current_time)
        self._admit_new_arrivals(self.current_time)
        n_abandoned = self._apply_patience_decay()
        reward = self._compute_reward(n_abandoned)
        self._done = self.current_time >= self.episode_minutes
        info = {"stats": dict(self.stats), "kpis": self.kpis()}
        # A simulated working day is the task horizon, not an arbitrary timeout.
        return self._get_observation(), reward, self._done, False, info

    def _apply_action(self, action):
        """
        Action 0 = aucune réaffectation.

        Action 1 = renforcer ACCOUNT.
        Action 2 = renforcer CARD.
        Action 3 = renforcer CREDIT.
        Action 4 = renforcer ADVISORY.

        On ne réaffecte un conseiller que si :
        - la compétence cible possède des clients en attente ;
        - un conseiller libre existe ;
        - ce conseiller n'est pas déjà affecté à cette compétence.
        """

        if action == 0:
            return

        target_skill = SKILLS[action - 1]

        # -------------------------------------------------------------
        # IMPORTANT :
        # inutile de déplacer un conseiller vers une file vide
        # -------------------------------------------------------------

        if not self.queues[target_skill]:
            return

        # -------------------------------------------------------------
        # Chercher les conseillers libres
        # -------------------------------------------------------------

        state = self.api_state()
        donor = select_donor(state["queues"], state["available"], target_skill)
        if donor is None:
            return
        chosen = next(a for a in self.advisors
                      if a["busy_until"] is None and a["current_skill"] == donor)
        chosen["current_skill"] = target_skill
        self.reassignments += 1

    # -----------------------------------------------------------------
    # ARRIVÉES
    # -----------------------------------------------------------------

    def _admit_new_arrivals(self, window_end):
        """
        Ajoute dans les files les clients arrivés pendant la minute.
        """

        while (
            self.arrivals_queue
            and self.arrivals_queue[0]["arrival_time"] <= window_end
        ):

            client = self.arrivals_queue.pop(0)

            client["patience"] = 100.0

            skill = client["skill_needed"]

            self.queues[skill].append(client)

    # -----------------------------------------------------------------
    # AFFECTATION DES CONSEILLERS
    # -----------------------------------------------------------------

    def _assign_advisors(self):
        """
        Affecte les conseillers libres aux clients en attente.

        FIFO :
        le premier client arrivé est traité en premier.
        """

        for skill in SKILLS:

            queue = self.queues[skill]

            free_advisors = [
                advisor
                for advisor in self.advisors
                if (
                    advisor["busy_until"] is None
                    and advisor["current_skill"] == skill
                )
            ]

            while queue and free_advisors:

                client = queue.pop(0)

                advisor = free_advisors.pop(0)

                # Durée moyenne d'un appel = 3 minutes
                duration = client["service_duration"]

                advisor["busy_until"] = (
                    self.current_time + duration
                )

                # Temps d'attente du client
                wait_time = (
                    self.current_time
                    - client["arrival_time"]
                )

                if wait_time < 0:
                    raise RuntimeError("Service before arrival")
                self.wait_seconds.append(wait_time * 60)

                # Client pris en charge
                self.stats["total"] += 1
                self.stats["resolved"] += 1

                # SLA <= 60 secondes
                if wait_time * 60 <= SLA_TARGET_SECONDS:
                    self.stats["sla_met"] += 1

    # -----------------------------------------------------------------
    # LIBÉRATION DES CONSEILLERS
    # -----------------------------------------------------------------

    def _free_finished_advisors(self, window_end):
        """
        Libère les conseillers dont l'appel est terminé.

        Après l'appel, le conseiller revient à sa compétence d'origine.
        """

        for advisor in self.advisors:

            if (
                advisor["busy_until"] is not None
                and advisor["busy_until"] <= window_end
            ):

                advisor["busy_until"] = None

                # Retour à la compétence d'origine
                advisor["current_skill"] = advisor["home_skill"]

    # -----------------------------------------------------------------
    # ABANDONS
    # -----------------------------------------------------------------

    def _apply_patience_decay(self):
        """
        Diminue la patience des clients en attente.

        Si patience <= 0 :
        le client abandonne.
        """

        n_abandoned = 0

        for skill in SKILLS:

            remaining = []

            for client in self.queues[skill]:

                profile = client["profile"]

                decay = (
                    PATIENCE_DECAY[profile]
                    * STEP_MINUTES
                )

                client["patience"] = 100.0 - (self.current_time - client["arrival_time"]) * PATIENCE_DECAY[profile]

                if client["patience"] <= 0:

                    n_abandoned += 1

                    self.stats["abandoned"] += 1
                    self.stats["total"] += 1

                else:

                    remaining.append(client)

            self.queues[skill] = remaining

        return n_abandoned

    # -----------------------------------------------------------------
    # REWARD
    # -----------------------------------------------------------------

    def _compute_reward(self, n_abandoned):
        served = self.stats["resolved"] - self._step_before["resolved"]
        sla = self.stats["sla_met"] - self._step_before["sla_met"]
        waiting = sum(len(q) for q in self.queues.values())
        # Local counts use the same fixed scale; no cumulative SLA bonus.
        capacity = max(1., len(self.advisors) / 3.)
        return float((0.4 * sla + 0.1 * served - 0.35 * n_abandoned
                      - 0.15 * waiting / 10.) / capacity)

    def kpis(self):
        served = self.stats["resolved"]
        return {
            "served": served,
            "sla_rate": self.stats["sla_met"] / max(1, served),
            "sla_all_arrivals": self.stats["sla_met"] / max(1, self.arrived_count),
            "abandon_rate": self.stats["abandoned"] / max(1, self.arrived_count),
            "avg_wait_seconds": float(np.mean(self.wait_seconds)) if self.wait_seconds else 0.,
            "p95_wait_seconds": float(np.percentile(self.wait_seconds, 95)) if self.wait_seconds else 0.,
            "pending": sum(len(q) for q in self.queues.values()),
            "reassignments": self.reassignments,
        }

    def api_state(self):
        obs = self._get_observation()
        return {
            "queues": {s: int(obs[i]) for i, s in enumerate(SKILLS)},
            "avg_wait": {s: float(obs[4+i]) * 60 for i, s in enumerate(SKILLS)},
            "available": {s: int(obs[8+i]) for i, s in enumerate(SKILLS)},
            "sla_today": float(obs[12]), "hour": int(obs[13]), "trend": float(obs[14]),
        }

    def _get_observation(self):
        """
        Construit l'observation de 15 valeurs.

        [0:4]   = tailles des files
        [4:8]   = attentes moyennes
        [8:12]  = conseillers disponibles
        [12]    = ratio SLA
        [13]    = heure
        [14]    = tendance
        """

        # -------------------------------------------------------------
        # 1. Taille des files
        # -------------------------------------------------------------

        queue_lengths = [
            len(self.queues[skill])
            for skill in SKILLS
        ]

        # -------------------------------------------------------------
        # 2. Temps d'attente moyen
        # -------------------------------------------------------------

        avg_waits = []

        for skill in SKILLS:

            queue = self.queues[skill]

            if queue:

                waits = [
                    self.current_time - client["arrival_time"]
                    for client in queue
                ]

                avg_wait = sum(waits) / len(waits)

            else:

                avg_wait = 0.0

            avg_waits.append(avg_wait)

        # -------------------------------------------------------------
        # 3. Conseillers disponibles
        # -------------------------------------------------------------

        availability = [
            sum(
                1
                for advisor in self.advisors
                if (
                    advisor["busy_until"] is None
                    and advisor["current_skill"] == skill
                )
            )
            for skill in SKILLS
        ]

        # -------------------------------------------------------------
        # 4. SLA
        # -------------------------------------------------------------

        if self.stats["resolved"] > 0:

            sla_ratio = (
                self.stats["sla_met"]
                / self.stats["resolved"]
            )

        else:

            sla_ratio = 1.0

        # -------------------------------------------------------------
        # 5. Heure simulée
        # -------------------------------------------------------------

        hour = 8 + (self.current_time // 60)

        # -------------------------------------------------------------
        # 6. Trend
        # -------------------------------------------------------------
        # Pour l'instant, valeur neutre.
        trend = 1.0

        # -------------------------------------------------------------
        # Construction finale
        # -------------------------------------------------------------

        obs = (
            queue_lengths
            + avg_waits
            + availability
            + [sla_ratio, hour, trend]
        )

        return np.array(
            obs,
            dtype=np.float32,
        )