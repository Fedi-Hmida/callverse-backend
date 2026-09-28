from app.workforce.env import CallCenterEnv

env = CallCenterEnv(n_advisors_per_skill=3, episode_minutes=60)
obs, info = env.reset(seed=42)
print("Observation initiale :", obs)

total_reward = 0
for i in range(10):
    action = 0  # "ne rien faire", pour l'instant
    obs, reward, terminated, truncated, info = env.step(action)
    total_reward += reward
    print(f"Pas {i+1} — reward: {reward:.3f}, stats: {info['stats']}")

print(f"\nRécompense totale sur 10 pas : {total_reward:.3f}")