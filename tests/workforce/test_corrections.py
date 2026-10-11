import numpy as np
from app.workforce.env import CallCenterEnv
from app.workforce.actions import select_donor
from app.workforce.policy import state_to_observation
from app.contracts.workforce import WorkforceState

def test_no_service_before_arrival():
    env = CallCenterEnv(episode_minutes=2)
    env.reset(seed=42)
    env.arrivals_queue = [{"arrival_time": .75, "skill_needed": "ACCOUNT",
                           "profile": "CALME", "service_duration": 3.}]
    env.arrived_count = 1
    env.step(0)
    assert env.stats["resolved"] == 0
    assert env.queues["ACCOUNT"][0]["patience"] == 98.75
    env.step(0)
    assert env.wait_seconds == [15.]
    assert env.stats["resolved"] == 1

def test_finished_advisor_is_available_for_next_decision():
    env = CallCenterEnv(episode_minutes=3)
    env.reset(seed=42)
    env.arrivals_queue = []
    advisor = env.advisors[0]
    advisor["busy_until"] = .5
    env.step(0)
    assert advisor["busy_until"] is None

def test_api_and_environment_reassignment_match():
    env = CallCenterEnv(episode_minutes=3)
    env.reset(seed=42)
    env.arrivals_queue = []
    env.queues["CREDIT"] = [{"arrival_time": 0., "profile": "CALME",
                             "patience": 100., "service_duration": 3.}]
    state = env.api_state()
    assert select_donor(state["queues"], state["available"], "CREDIT") == "ACCOUNT"
    env._apply_action(3)
    assert env.advisors[0]["current_skill"] == "CREDIT"
    assert env.reassignments == 1
    assert np.allclose(state_to_observation(WorkforceState(**env.api_state())),
                       env._get_observation())

def test_same_seed_same_clients_and_service_times():
    a, b = CallCenterEnv(), CallCenterEnv()
    a.reset(seed=12)
    b.reset(seed=12)
    assert a.arrivals_queue == b.arrivals_queue
    for _ in range(10):
        a.step(0)
        b.step(1)
    assert all(w >= 0 for w in a.wait_seconds + b.wait_seconds)

def test_recent_reward_has_no_bonus_without_service():
    env = CallCenterEnv(episode_minutes=2)
    env.reset(seed=42)
    env.arrivals_queue = []
    _, reward, _, _, _ = env.step(0)
    assert reward == 0

def test_repeatable_episode_and_kpi_conservation():
    outputs = []
    for _ in range(2):
        env = CallCenterEnv(episode_minutes=30, arrival_scale=.35)
        obs, _ = env.reset(seed=42)
        for _ in range(30):
            obs, reward, done, _, info = env.step(0)
        assert done
        assert env.arrived_count == env.stats["resolved"] + env.stats["abandoned"] + info["kpis"]["pending"]
        outputs.append(info)
    assert outputs[0] == outputs[1]
