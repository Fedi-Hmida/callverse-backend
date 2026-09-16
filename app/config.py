"""
Settings centralisées (pydantic-settings).
TODO: URL backend Spring Boot, clé LLM, DSN pgvector.
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    backend_base_url: str = "http://localhost:8080"
    llm_api_key: str = ""
    pgvector_dsn: str = "postgresql://user:pass@localhost:5432/callverse"

    class Config:
        env_file = ".env"


settings = Settings()
