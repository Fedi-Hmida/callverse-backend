"""
Test des baselines STATIC_FIFO et THRESHOLD
dans l'environnement CallCenterEnv.

On utilise les mêmes seeds pour permettre ensuite
la comparaison avec PPO.
"""

from app.workforce.env import CallCenterEnv
from app.workforce.baselines import static_fifo, threshold


SEEDS = [42, 43, 44, 45, 46]
EPISODE_MINUTES = 60
THRESHOLD_VALUE = 10


def build_state(env):
    return env.api_state()


def apply_baseline_action(env, result):
    action = result['action']
    if action['type'] == 'REASSIGN':
        from app.workforce.env import SKILLS
        env._apply_action(SKILLS.index(action['to_pool']) + 1)


def run_baseline(baseline_function, seed, threshold_value=None):
    """
    Exécute une baseline pendant un épisode complet.
    """

    env = CallCenterEnv(
        episode_minutes=EPISODE_MINUTES
    )

    obs, info = env.reset(seed=seed)

    total_reward = 0.0

    for _ in range(EPISODE_MINUTES):

        state = build_state(env)

        if threshold_value is not None:
            result = baseline_function(
                state,
                n=threshold_value
            )
        else:
            result = baseline_function(state)

        apply_baseline_action(env, result)

        obs, reward, terminated, truncated, info = env.step(0)

        total_reward += reward

        if terminated or truncated:
            break

    return {
        "reward": total_reward,
        "resolved": env.stats["resolved"],
        "abandoned": env.stats["abandoned"],
        "sla_met": env.stats["sla_met"],
        "total": env.stats["total"],
    }


def print_results(name, results):
    """
    Affiche les résultats d'une baseline.
    """

    print("\n" + "=" * 60)
    print(name)
    print("=" * 60)

    total_reward = 0.0
    total_resolved = 0
    total_abandoned = 0
    total_sla = 0
    total_clients = 0

    for seed, result in results.items():

        print(f"\nSeed {seed}")
        print(f"  Reward      : {result['reward']:.2f}")
        print(f"  Résolus     : {result['resolved']}")
        print(f"  Abandonnés  : {result['abandoned']}")
        print(f"  SLA respecté: {result['sla_met']}")
        print(f"  Total       : {result['total']}")

        total_reward += result["reward"]
        total_resolved += result["resolved"]
        total_abandoned += result["abandoned"]
        total_sla += result["sla_met"]
        total_clients += result["total"]

    n = len(results)

    print("\n" + "-" * 60)
    print("MOYENNE")
    print("-" * 60)

    print(f"Reward moyen       : {total_reward / n:.2f}")
    print(f"Résolus moyens     : {total_resolved / n:.2f}")
    print(f"Abandons moyens    : {total_abandoned / n:.2f}")
    print(f"SLA moyen          : {total_sla / n:.2f}")
    print(f"Total moyen        : {total_clients / n:.2f}")


def main():

    # =========================================================
    # STATIC FIFO
    # =========================================================

    fifo_results = {}

    for seed in SEEDS:

        fifo_results[seed] = run_baseline(
            static_fifo,
            seed
        )

    print_results(
        "STATIC_FIFO",
        fifo_results
    )

    # =========================================================
    # THRESHOLD
    # =========================================================

    threshold_results = {}

    for seed in SEEDS:

        threshold_results[seed] = run_baseline(
            threshold,
            seed,
            threshold_value=THRESHOLD_VALUE
        )

    print_results(
        f"THRESHOLD (n={THRESHOLD_VALUE})",
        threshold_results
    )


if __name__ == "__main__":
    main()