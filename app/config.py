"""
Settings centralisées (pydantic-settings).
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    workforce_model_path: str = "artifacts/workforce_improved.zip"
    quality_ollama_url: str = "http://localhost:11434"
    quality_model: str = ""
    quality_timeout_seconds: float = 120.0

    backend_base_url: str = "http://localhost:8080"

    llm_api_key: str = ""
    llm_provider: str = "groq"
    ollama_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:3b"
    llm_model_light: str = "openai/gpt-oss-20b"
    llm_model_advanced: str = "openai/gpt-oss-120b"

    pgvector_dsn: str = "postgresql://user:pass@localhost:5432/callverse"


settings = Settings()
