"""Entraînement PPO sur trois charges ; artefact distinct du modèle existant."""
import argparse
from pathlib import Path
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.callbacks import EvalCallback
from app.workforce.env import CallCenterEnv
from app.workforce.features import WorkforceFeatures

def train(total_timesteps=100_000, save_path="artifacts/workforce_v2", seed=7):
    torch.set_num_threads(2)
    output = Path(save_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    env = Monitor(CallCenterEnv(training_scales=(.2, .35, 1.)))
    evaluation = Monitor(CallCenterEnv(arrival_scale=.35))
    evaluation.reset(seed=1000)
    callback = EvalCallback(evaluation, best_model_save_path=str(output.parent / "best"),
                            log_path=str(output.parent / "eval"),
                            eval_freq=10_000, n_eval_episodes=5, deterministic=True)
    model = PPO("MlpPolicy", env, seed=seed, device="cpu", verbose=0,
                learning_rate=3e-4, n_steps=1024, batch_size=128,
                ent_coef=.01, policy_kwargs={
                    "features_extractor_class": WorkforceFeatures,
                    "net_arch": dict(pi=[64,64], vf=[64,64])})
    try:
        model.learn(total_timesteps=total_timesteps, callback=callback)
        model.save(str(output))
    finally:
        env.close()
        evaluation.close()
    return str(output.with_suffix(".zip"))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--timesteps", type=int, default=100_000)
    parser.add_argument("--save-path", default="artifacts/workforce_v2")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    print(train(args.timesteps, args.save_path, args.seed))
