"""
Script d'entraînement PPO (Stable-Baselines3) sur CallCenterEnv.
Ne rien persister pendant l'entraînement (TensorBoard/CSV local seulement).
TODO (Ines).
"""
from stable_baselines3 import PPO

from .env import CallCenterEnv


def train():
    env = CallCenterEnv()
    model = PPO("MlpPolicy", env)
    model.learn(total_timesteps=1_000_000)
    model.save("workforce_policy")


if __name__ == "__main__":
    train()
