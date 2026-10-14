"""
Générateur d'arrivées — processus de Poisson, lambda variable selon l'heure,
+ événements injectés (incident réseau, pic de facturation).
TODO (Ines).
"""
"""
Générateur d'arrivées — processus de Poisson, lambda variable selon l'heure,
+ événements injectés (incident réseau, pic de facturation).
"""

import random
from dataclasses import dataclass


# ---------------------------------------------------------------------
# Barème horaire (doc Workforce Manager, §1)
# Clé = heure de début de la tranche, valeur = lambda (clients/minute)
# ---------------------------------------------------------------------
HOURLY_LAMBDA = {
    8: 12,   # 08h-10h : pic matinal
    10: 8,   # 10h-12h
    12: 4,   # 12h-14h : creux déjeuner
    14: 9,   # 14h-17h : reprise
    17: 6,   # 17h-19h : fin de journée
}

# Motif -> compétence requise (mapping métier)
MOTIF_TO_SKILL = {
    "ACCOUNT": "ACCOUNT",
    "CARD": "CARD",
    "CREDIT": "CREDIT",
    "ADVISORY": "ADVISORY",
}

MOTIF_WEIGHTS = {
    "ACCOUNT": 0.30,
    "CARD": 0.30,
    "CREDIT": 0.25,
    "ADVISORY": 0.15,
}

PROFILE_WEIGHTS = {
    "CALME": 0.40,
    "IMPATIENT": 0.25,
    "INSATISFAIT": 0.20,
    "EXIGEANT": 0.10,
    "CHURN_RISK": 0.05,
}


@dataclass
class InjectedEvent:
    """Un événement temporaire qui multiplie le lambda (ex: incident réseau)."""
    start_time: float      # en minutes, depuis le début de la simulation
    duration: float        # en minutes
    multiplier: float      # ex: 4.0 pour "x4"


def get_base_lambda(sim_time_minutes: float, start_hour: int = 8) -> float:
    """
    Retourne le lambda (clients/minute) correspondant à l'heure simulée.
    sim_time_minutes : temps écoulé depuis le début de la simulation.
    start_hour : heure de la journée à laquelle la simulation démarre.
    """
    current_hour = start_hour + int(sim_time_minutes // 60)
    # On cherche la tranche horaire applicable (la plus grande clé <= current_hour)
    applicable_hours = [h for h in HOURLY_LAMBDA if h <= current_hour]
    if not applicable_hours:
        return list(HOURLY_LAMBDA.values())[0]  # avant 8h, valeur par défaut
    hour_key = max(applicable_hours)
    return HOURLY_LAMBDA[hour_key]


def get_effective_lambda(
    sim_time_minutes: float,
    start_hour: int = 8,
    events: list[InjectedEvent] | None = None,
) -> float:
    """
    Applique les événements injectés (incident réseau, etc.) par-dessus
    le lambda de base.
    """
    base = get_base_lambda(sim_time_minutes, start_hour)
    if not events:
        return base

    multiplier = 1.0
    for event in events:
        if event.start_time <= sim_time_minutes < event.start_time + event.duration:
            multiplier *= event.multiplier
    return base * multiplier


def draw_motif() -> str:
    """Tire un motif de contact selon la répartition définie."""
    motifs = list(MOTIF_WEIGHTS.keys())
    weights = list(MOTIF_WEIGHTS.values())
    return random.choices(motifs, weights=weights, k=1)[0]


def draw_profile() -> str:
    """Tire un profil comportemental client."""
    profiles = list(PROFILE_WEIGHTS.keys())
    weights = list(PROFILE_WEIGHTS.values())
    return random.choices(profiles, weights=weights, k=1)[0]


def generate_arrivals(
    duration_minutes: float,
    start_hour: int = 8,
    events: list[InjectedEvent] | None = None,
    rng_seed: int | None = None,
    arrival_scale: float = 1.0,
) -> list[dict]:
    """
    Génère la liste complète des arrivées pour une simulation de
    `duration_minutes` minutes.

    Retourne une liste de dicts, chacun représentant un client qui arrive :
    {

    "arrival_time": 12.4,
    "skill_needed": "ACCOUNT",
    "motif": "ACCOUNT",
    "profile": "IMPATIENT",

    }
    """
    if arrival_scale <= 0:
        raise ValueError("arrival_scale must be positive")
    rng = random.Random(rng_seed)

    arrivals = []
    current_time = 0.0

    while current_time < duration_minutes:
        lam = get_effective_lambda(current_time, start_hour, events) * arrival_scale
        if lam <= 0:
            break

        # Temps avant la prochaine arrivée (loi exponentielle)
        inter_arrival = rng.expovariate(lam)
        current_time += inter_arrival

        if current_time >= duration_minutes:
            break

        motif = rng.choices(list(MOTIF_WEIGHTS), weights=list(MOTIF_WEIGHTS.values()), k=1)[0]
        arrivals.append({
            "arrival_time": current_time,
            "skill_needed": MOTIF_TO_SKILL[motif],
            "motif": motif,
            "profile": rng.choices(list(PROFILE_WEIGHTS), weights=list(PROFILE_WEIGHTS.values()), k=1)[0],
        })

    return arrivals
