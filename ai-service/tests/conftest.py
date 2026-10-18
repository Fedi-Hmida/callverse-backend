"""
Configuration commune des tests.

app.main refuse de démarrer sans AI_SERVICE_KEY (fail closed). Les tests qui importent
l'application entière utilisent donc l'opt-out explicite de développement ; les tests
d'authentification construisent leur propre application et vérifient le cas fermé.
"""
import os

os.environ.setdefault("AI_SERVICE_AUTH_DISABLED", "true")
