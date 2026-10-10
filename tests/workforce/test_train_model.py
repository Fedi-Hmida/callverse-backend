from stable_baselines3 import PPO
from app.workforce.env import CallCenterEnv


MODEL_PATH = "workforce_policy.zip"
SEEDS = [42, 43, 44, 45, 46]


for seed in SEEDS:

    print("\n" + "=" * 50)
    print(f"TEST AVEC SEED {seed}")
    print("=" * 50)

    env = CallCenterEnv(episode_minutes=60)

    obs, info = env.reset(seed=seed)

    model = PPO.load(MODEL_PATH)

    total_reward = 0.0

    for step in range(60):

        action, _ = model.predict(obs, deterministic=True)

        obs, reward, terminated, truncated, info = env.step(int(action))

        total_reward += reward

        if terminated or truncated:
            break

    stats = info["stats"]

    print(f"Reward total : {total_reward:.2f}")
    print(f"Résolus     : {stats['resolved']}")
    print(f"Abandonnés  : {stats['abandoned']}")
    print(f"SLA respecté: {stats['sla_met']}")
    print(f"Total       : {stats['total']}")