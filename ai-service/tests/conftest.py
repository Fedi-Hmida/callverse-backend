"""
Configuration commune des tests.

app.main refuse de démarrer sans AI_SERVICE_KEY (fail closed). Les tests qui importent
l'application entière utilisent donc l'opt-out explicite de développement ; les tests
d'authentification construisent leur propre application et vérifient le cas fermé.
"""
import os

# In-process tests must not inherit local credentials or deployment timeouts.
# Authentication tests construct a separate closed application with explicit keys.
os.environ.update({
    "AI_SERVICE_AUTH_DISABLED": "true",
    "AI_SERVICE_KEY": "",
    "INTERNAL_SERVICE_KEY": "unused-test-key",
    "PGVECTOR_DSN": "postgresql://test:test@localhost/test",
    "AI_REQUEST_DEADLINE_SECONDS": "25",
    "QUALITY_TIMEOUT_SECONDS": "120",
})
if os.environ.get("CALLVERSE_TEST_REAL_LLM") != "1":
    os.environ["LLM_API_KEY"] = "unused-test-key"
