"""
Grille d'évaluation du Quality Analyst : 6 critères pondérés (secteur bancaire).
"""

from dataclasses import dataclass

SCORE_MIN = 0
SCORE_MAX = 10


@dataclass(frozen=True)
class Criterion:
    code: str        # identifiant technique, identique aux clés du contrat API
    label: str       # nom lisible
    weight: float    # poids dans la note globale
    question: str    # la question posée au juge pour ce critère


RUBRIC: list[Criterion] = [
    Criterion("relevance", "Pertinence", 0.15,
              "La réponse traite-t-elle la demande posée ?"),
    Criterion("accuracy", "Exactitude", 0.20,
              "L'information donnée est-elle correcte ?"),
    Criterion("compliance", "Conformité", 0.25,
              "La procédure bancaire a-t-elle été respectée ?"),
    Criterion("communication", "Communication", 0.10,
              "La réponse est-elle claire, structurée, sans jargon ?"),
    Criterion("empathy", "Empathie", 0.10,
              "Le conseiller reconnaît-il la situation du client ?"),
    Criterion("resolution", "Résolution", 0.20,
              "Le problème est-il effectivement réglé ?"),
]


def validate_rubric(rubric: list[Criterion] = RUBRIC) -> None:
    """Vérifie que les poids somment bien à 1."""
    total = sum(c.weight for c in rubric)
    if abs(total - 1.0) > 1e-9:
        raise ValueError(f"Les poids doivent sommer à 1.0, obtenu {total}")


def compute_global_score(scores: dict[str, float],
                         rubric: list[Criterion] = RUBRIC) -> float:
    """Moyenne pondérée des notes par critère, sur une échelle de 0 à 10."""
    validate_rubric(rubric)
    expected = {c.code for c in rubric}
    if set(scores) != expected:
        raise ValueError(f"Critères attendus : {sorted(expected)}, reçus : {sorted(scores)}")

    for code, value in scores.items():
        if not SCORE_MIN <= value <= SCORE_MAX:
            raise ValueError(f"Note hors de [{SCORE_MIN}, {SCORE_MAX}] pour {code} : {value}")

    total = sum(scores[c.code] * c.weight for c in rubric)
    return round(total, 2)