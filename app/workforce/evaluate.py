"""Comparaison reproductible des stratégies sur les mêmes clients."""
import argparse
import json
from pathlib import Path
import numpy as np
from stable_baselines3 import PPO
from app.workforce.env import CallCenterEnv
from app.workforce.baselines import threshold

def evaluate_models(paths, seeds=range(42, 47), minutes=480):
    models = {Path(p).stem: PPO.load(p, device="cpu") for p in paths}
    strategies = {"STATIC_FIFO": None, "THRESHOLD": None, **models}
    rows = []
    for load_name, scale in [("low", .2), ("medium", .35), ("saturated", 1.)]:
        for name, model in strategies.items():
            runs = []
            for seed in seeds:
                env = CallCenterEnv(episode_minutes=minutes, arrival_scale=scale)
                obs, _ = env.reset(seed=seed)
                done = False
                while not done:
                    if model is not None:
                        action = int(model.predict(obs, deterministic=True)[0])
                    elif name == "THRESHOLD":
                        result = threshold(env.api_state(), n=3)["action"]
                        action = list(env.queues).index(result["to_pool"]) + 1 if result["type"] == "REASSIGN" else 0
                    else:
                        action = 0
                    obs, _, terminated, truncated, info = env.step(action)
                    done = terminated or truncated
                runs.append(info["kpis"])
                env.close()
            rows.append({"load": load_name, "strategy": name, "n": len(runs),
                         "metrics": {k: {"mean": float(np.mean([r[k] for r in runs])),
                                         "std": float(np.std([r[k] for r in runs], ddof=1)) if len(runs)>1 else 0.}
                                     for k in runs[0]}})
    return rows

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", nargs="*", default=["workforce_policy.zip"])
    parser.add_argument("--output", default="workforce_evaluation.json")
    args = parser.parse_args()
    rows = evaluate_models(args.models)
    Path(args.output).write_text(json.dumps(rows, indent=2), encoding="utf-8")
    for row in rows:
        m = row["metrics"]
        print(row["load"], row["strategy"],
              "SLA", round(m["sla_rate"]["mean"], 3),
              "abandon", round(m["abandon_rate"]["mean"], 3),
              "wait(s)", round(m["avg_wait_seconds"]["mean"], 1))
