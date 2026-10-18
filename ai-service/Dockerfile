# Image du service IA CallVerse (FastAPI : Advisor, Simulator, Quality, Workforce).
# CPU uniquement : la politique PPO et les embeddings tournent sans GPU.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Versions exactes (requirements.lock, torch CPU inclus) : deux builds donnent la même image.
COPY requirements.lock .
RUN pip install -r requirements.lock

COPY app ./app
# Seul le modèle servi par défaut (workforce_model_path) entre dans l'image.
COPY artifacts/workforce_banking_v1 ./artifacts/workforce_banking_v1

# Jamais root ; le cache du modèle d'embeddings s'écrit dans le répertoire de cet utilisateur.
RUN useradd --create-home --uid 10001 callverse
USER callverse
# Créé par l'utilisateur : un volume nommé monté ici hérite de ce propriétaire (sinon root).
RUN mkdir -p /home/callverse/.cache

EXPOSE 8000
# Aucun secret dans l'image : AI_SERVICE_KEY, INTERNAL_SERVICE_KEY, llm_api_key et pgvector_dsn
# arrivent par l'environnement. Sans AI_SERVICE_KEY, le service refuse de démarrer.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
