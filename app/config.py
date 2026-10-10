"""
Settings centralisées (pydantic-settings).
TODO: URL backend Spring Boot, clé LLM, DSN pgvector.
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    backend_base_url: str = "http://localhost:8080"
    llm_api_key: str = ""
    workforce_model_path: str = "artifacts/workforce_improved.zip"
    quality_ollama_url: str = "http://localhost:11434"
    quality_model: str = ""
    quality_timeout_seconds: float = 120.0
    pgvector_dsn: str = "postgresql://user:pass@localhost:5432/callverse"

    class Config:
        env_file = ".env"


settings = Settings()
