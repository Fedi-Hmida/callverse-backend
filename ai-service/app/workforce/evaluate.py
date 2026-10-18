"""Comparaison reproductible des stratégies sur les mêmes clients."""
import argparse
import json
from pathlib import Path
import numpy as np
from app.workforce.twin import run_episode

def evaluate_models(paths, seeds=range(42, 47), minutes=480):
    models = {}
    if paths:
        # Import différé : comparer les seules baselines ne demande ni torch ni stable-baselines3.
        from stable_baselines3 import PPO
        models = {Path(p).stem: PPO.load(p, device="cpu") for p in paths}
    strategies = {"STATIC_FIFO": None, "THRESHOLD": None, **models}
    rows = []
    for load_name, scale in [("low", .2), ("medium", .35), ("saturated", 1.)]:
        for name, model in strategies.items():
            # Même épisode que l'API du jumeau (twin.run_episode) : chiffres identiques.
            strategy = "PPO" if model is not None else name
            runs = [run_episode(strategy, seed, scale, minutes, advisors_per_skill=3, model=model)
                    for seed in seeds]
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
