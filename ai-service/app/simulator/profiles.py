"""
Les 6 profils clients : calme, impatient, insatisfait, exigeant,
intéressé par une offre, à risque de départ (transaction suspecte / churn).
Chacun a son taux de décroissance de patience, ses règles de satisfaction,
son style de langage. La mise à jour d'état reste déterministe (code Python),
le LLM ne sert qu'à formuler le message.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class ProfileConfig:
    label: str
    style: str
    # décroissance de patience par tour, en l'absence de réponse utile
    patience_decay_base: float
    # multiplicateur de décroissance si la réponse du conseiller est jugée vague
    patience_decay_on_vague: float
    # gain/perte de satisfaction selon la qualité perçue de la réponse
    satisfaction_gain_useful: float
    satisfaction_loss_vague: float
    # patience en dessous de laquelle le client abandonne
    abandon_threshold: float
    # exemples d'objectifs types pour ce profil (domaine bancaire)
    objective_examples: tuple[str, ...]


PROFILES: dict[str, ProfileConfig] = {
    "calme": ProfileConfig(
        label="Client calme",
        style="poli, patient, formule des phrases complètes, ne hausse jamais le ton",
        patience_decay_base=1.5,
        patience_decay_on_vague=1.2,
        satisfaction_gain_useful=8.0,
        satisfaction_loss_vague=3.0,
        abandon_threshold=5.0,
        objective_examples=(
            "comprendre un frais prélevé sur son compte",
            "connaître les conditions d'un livret",
        ),
    ),
    "impatient": ProfileConfig(
        label="Client impatient",
        style="phrases courtes, relance vite, menace de raccrocher si ça traîne",
        patience_decay_base=4.0,
        patience_decay_on_vague=2.0,
        satisfaction_gain_useful=6.0,
        satisfaction_loss_vague=6.0,
        abandon_threshold=15.0,
        objective_examples=(
            "faire opposition sur une carte perdue au plus vite",
            "débloquer un virement urgent",
        ),
    ),
    "insatisfait": ProfileConfig(
        label="Client insatisfait",
        style="ton froid, rappelle un problème déjà signalé, peu de patience résiduelle",
        patience_decay_base=3.0,
        patience_decay_on_vague=2.5,
        satisfaction_gain_useful=5.0,
        satisfaction_loss_vague=8.0,
        abandon_threshold=12.0,
        objective_examples=(
            "obtenir le remboursement de frais déjà réclamé une fois",
            "faire corriger une erreur récurrente sur son relevé",
        ),
    ),
    "exigeant": ProfileConfig(
        label="Client exigeant",
        style="pose des questions précises, demande des justifications, teste la compétence",
        patience_decay_base=2.0,
        patience_decay_on_vague=3.0,
        satisfaction_gain_useful=7.0,
        satisfaction_loss_vague=7.0,
        abandon_threshold=10.0,
        objective_examples=(
            "obtenir le détail exact des conditions d'un crédit",
            "comprendre pourquoi son plafond de carte n'a pas changé",
        ),
    ),
    "offre": ProfileConfig(
        label="Client intéressé par une offre",
        style="curieux, pose des questions ouvertes, pas pressé, cherche à comparer",
        patience_decay_base=1.0,
        patience_decay_on_vague=1.5,
        satisfaction_gain_useful=6.0,
        satisfaction_loss_vague=2.0,
        abandon_threshold=8.0,
        objective_examples=(
            "se renseigner sur une offre d'épargne",
            "comparer deux formules de crédit conso",
        ),
    ),
    "churn": ProfileConfig(
        label="Client à risque (transaction suspecte)",
        style="inquiet, direct, veut une action immédiate, peu tolérant à l'attente",
        patience_decay_base=5.0,
        patience_decay_on_vague=3.0,
        satisfaction_gain_useful=10.0,
        satisfaction_loss_vague=10.0,
        abandon_threshold=20.0,
        objective_examples=(
            "faire bloquer sa carte suite à une transaction non reconnue",
            "obtenir la confirmation qu'une fraude a été prise en compte",
        ),
    ),
}
