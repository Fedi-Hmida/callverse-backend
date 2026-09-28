"""
Script d'entraînement PPO (Stable-Baselines3) sur CallCenterEnv.
Ne rien persister pendant l'entraînement (TensorBoard/CSV local seulement).
"""

from stable_baselines3 import PPO
from stable_baselines3.common.monitor import Monitor

from .env import CallCenterEnv


def train(total_timesteps: int = 100_000, save_path: str = "workforce_policy"):
    """
    Entraîne un agent PPO sur l'environnement CallCenterEnv.

    total_timesteps : nombre total de "pas" de simulation utilisés pour
                       l'apprentissage. Plus c'est grand, mieux l'agent
                       apprend, mais plus l'entraînement est long.
    """
    # Monitor enregistre les statistiques de chaque épisode (récompense totale, etc.)
    # dans un simple fichier CSV local — pas de persistance en base de données
    env = Monitor(CallCenterEnv(episode_minutes=480))

    model = PPO(
        "MlpPolicy",       # réseau de neurones simple, adapté à des observations numériques
        env,
        verbose=1,          # affiche la progression dans le terminal
        tensorboard_log="./tensorboard_logs/",  # pour visualiser l'apprentissage
    )

    model.learn(total_timesteps=total_timesteps)
    model.save(save_path)

    print(f"Entraînement terminé. Modèle sauvegardé dans {save_path}.zip")


if __name__ == "__main__":
    train()