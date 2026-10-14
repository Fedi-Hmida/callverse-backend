# CallVerse — AI Service

Service FastAPI hébergeant les 4 agents IA de CallVerse.
Voir `ARCHITECTURE.md` pour le détail de l'arborescence et des contrats partagés.

## Répartition
- **Douaa** : `app/advisor/`, `app/simulator/`
- **Ines** : `app/workforce/`, `app/quality/`
- **Ensemble** : `app/contracts/` (à figer en premier, revue croisée obligatoire)

## Démarrer
```bash
pip install -r requirements.txt
# ou : pip install -r requirements-dev.txt   (inclut pytest)
uvicorn app.main:app --reload
```
