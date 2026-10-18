from app.workforce.env import CallCenterEnv


env = CallCenterEnv(
    n_advisors_per_skill=3,
    episode_minutes=60
)

obs, info = env.reset(seed=42)

print("Observation initiale :")
print(obs)

print("Taille observation :", len(obs))

print("Action possible :", env.action_space)

for i in range(10):
    action = env.action_space.sample()

    obs, reward, terminated, truncated, info = env.step(action)

    print(f"\n--- Step {i + 1} ---")
    print("Action :", action)
    print("Observation :", obs)
    print("Reward :", reward)
    print("Stats :", info["stats"])

    if terminated or truncated:
        break