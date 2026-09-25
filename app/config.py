"""
Settings centralisées (pydantic-settings).
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    backend_base_url: str = "http://localhost:8080"

    llm_api_key: str = ""
    llm_provider: str = "groq"
    llm_model_light: str = "openai/gpt-oss-20b"
    llm_model_advanced: str = "openai/gpt-oss-120b"

    pgvector_dsn: str = "postgresql://user:pass@localhost:5432/callverse"


settings = Settings()
