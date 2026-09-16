"""
Environnement Gymnasium — CallCenterEnv.
TODO (Ines): observation_space, action_space, reset(), step().
"""
import gymnasium as gym
from gymnasium import spaces


class CallCenterEnv(gym.Env):
    def __init__(self):
        super().__init__()
        self.observation_space = spaces.Box(low=0, high=1, shape=(1,))  # TODO
        self.action_space = spaces.Discrete(1)  # TODO: garder petit (~10 actions max)

    def reset(self, seed=None, options=None):
        raise NotImplementedError

    def step(self, action):
        raise NotImplementedError
