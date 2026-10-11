"""Normalisation fixe intégrée au modèle : identique en entraînement et API."""
import torch
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor

class WorkforceFeatures(BaseFeaturesExtractor):
    def __init__(self, observation_space):
        super().__init__(observation_space, features_dim=15)
        self.register_buffer("scales", torch.tensor(
            [20.] * 4 + [10.] * 4 + [3.] * 4 + [1., 24., 2.]))

    def forward(self, observations):
        return observations / self.scales
