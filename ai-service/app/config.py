"""
Settings centralisées (pydantic-settings).
"""

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    workforce_model_path: str = "artifacts/workforce_banking_v1/best/best_model.zip"
    # Juge qualité : "groq" (défaut, même clé llm_api_key que l'Advisor) ou "ollama" (modèle local).
    # Sur CPU, Ollama dépassait le délai de lecture du backend (130 s) : aucune note n'était enregistrée.
    quality_provider: str = "groq"
    # Modèle léger : son quota Groq est distinct de celui de l'Advisor (gpt-oss-120b).
    quality_groq_model: str = "openai/gpt-oss-20b"
    quality_ollama_url: str = "http://localhost:11434"
    quality_model: str = ""
    quality_timeout_seconds: float = 120.0
    # Échéance globale d'une requête Advisor ou Simulator. Le backend abandonne à 30 s :
    # répondre 504 avant, plutôt que de continuer à appeler le LLM et les outils pour rien.
    ai_request_deadline_seconds: float = 25.0

    backend_base_url: str = Field(
        default="http://localhost:8080",
        validation_alias="CALLVERSE_BACKEND_BASE_URL",
    )
    internal_service_key: str = ""
    ai_service_key: str = ""
    # Local development only: lets the service start without AI_SERVICE_KEY (routes then open).
    ai_service_auth_disabled: bool = False

    llm_api_key: str = ""
    llm_provider: str = "groq"
    llm_model_light: str = "openai/gpt-oss-20b"
    llm_model_advanced: str = "openai/gpt-oss-120b"
    # A separate live model keeps automatic replies independent of the advanced model's quota.
    llm_live_advisor_model: str = ""
    # Optional alternative for a rate-limited live model; never replays banking tools.
    llm_live_advisor_fallback_model: str = ""
    # Empty retains the configured advanced model. A separate simulation model can be
    # selected explicitly without silently changing the model that handles live banking.
    llm_simulation_advisor_model: str = ""
    llm_advisor_max_output_tokens: int = Field(default=600, ge=128, le=4096)
    llm_advisor_history_messages: int = Field(default=10, ge=1, le=40)
    llm_advisor_history_chars: int = Field(default=6000, ge=1000, le=24000)

    pgvector_dsn: str = "postgresql://user:pass@localhost:5432/callverse"
    kb_index_refresh_seconds: float = Field(default=60, ge=0)


settings = Settings()
