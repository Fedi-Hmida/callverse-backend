# Quality Analyst bancaire

## Rôle et flux

Cet agent analyse une conversation terminée. Il ne s'entraîne pas par PPO :
un LLM sert de juge, puis Python contrôle et assemble son résultat.
Le Workforce Manager mesure les files et l'attente ; Quality mesure le
contenu et le respect des procédures. Une note n'est pas une autorisation
d'effectuer une opération bancaire.

1. `router.py` valide la requête avec le contrat Pydantic.
2. `judge.py` transmet la conversation, les références, les outils et la grille au modèle.
3. `prompts.py` définit les critères, les niveaux de note et les règles de citation.
4. `sourcing_check.py` contrôle les index, extraits et identifiants de documents.
5. `rubric.py` calcule la moyenne pondérée ; le modèle ne choisit pas le total.
6. Le résultat contient les notes, explications, recommandations et alertes.

## Grille du livret bancaire

| Critère | Poids | Pourquoi |
|---|---:|---|
| Conformité | 25 % | Respect des procédures et limites d'autorité |
| Exactitude | 20 % | Informations justifiées par les références |
| Résolution | 20 % | Issue obtenue ou escalade appropriée |
| Pertinence | 15 % | Réponse adaptée à la demande |
| Communication | 10 % | Explication claire |
| Empathie | 10 % | Reconnaissance de la situation |

Score global = somme(note × poids), arrondie à deux décimales.
Une note globale élevée peut masquer un défaut de conformité : regarder les
notes par critère et `procedure_violations`, pas seulement le total.

## Configuration locale

Ollama est le premier adaptateur utilisé ; aucune dépendance Python supplémentaire.
Choisir un modèle déjà installé, par exemple via `ollama list`, et configurer
dans `.env` :

```dotenv
QUALITY_MODEL=nom-exact-du-modele-installe
QUALITY_OLLAMA_URL=http://localhost:11434
QUALITY_TIMEOUT_SECONDS=600
```

Démarrer Ollama puis `uvicorn app.main:app --reload`.
Dans http://localhost:8000/docs, utiliser POST /ai/quality/evaluate.
Le transport utilise /api/chat, stream=false et le schéma JSON dans format :
[documentation Ollama](https://docs.ollama.com/capabilities/structured-outputs).

Exemple de corps :

```json
{
  "conversation_id": "carte-001",
  "messages": [
    {"sender": "CUSTOMER", "content": "J'ai perdu ma carte."},
    {"sender": "ADVISOR", "content": "Je comprends. Utilisez le canal sécurisé pour faire opposition."}
  ],
  "source_documents": [
    {"doc_id": "carte", "chunk_id": "1", "content": "En cas de perte, orienter vers le canal sécurisé d'opposition."}
  ],
  "applicable_rules": ["En cas de perte, orienter vers le canal sécurisé d'opposition."],
  "resolved": false
}
```

`advisor_response` est optionnel et reprend le contrat existant AdvisorResponse.
Son answer doit être le dernier message conseiller ; sources contient les
identifiants consultés, tool_calls les appels et leurs résultats.
Fournir les TEXTES des documents dans source_documents : un identifiant ou un
score de similarité ne permet pas de vérifier une affirmation.
Les références et règles doivent venir du backend/de la KB de la banque simulée.
Les exemples sont fictifs, pas des procédures bancaires universelles.

Contrat qualité enrichi : evidence conserve criterion, score et evidence,
et ajoute message_index et justification ; flags est typé et claims décrit
les affirmations. Les consommateurs frontend doivent prendre ces ajouts en compte.

## Interpréter le résultat

- SUPPORTED : affirmation appuyée par les références ou un outil cité.
- UNSUPPORTED : références pertinentes disponibles, mais affirmation non couverte.
- UNVERIFIABLE : éléments nécessaires absents.
- missing_source_keys : documents référencés par Advisor mais dont le texte manque.
- procedure_violations : défauts évalués par le modèle selon les règles fournies.

Les extraits sont vérifiés littéralement. Leur pertinence et l'appui sémantique
restent des jugements du modèle à valider humainement. Les résultats d'outils
concernent ici la dernière réponse Advisor ; transmettre l'historique complet
des outils est une prochaine extension du contrat pour les conversations longues.
Pas de score artificiel de secours : 422 = requête invalide, 503 = modèle
non configuré/indisponible, 502 = sortie du modèle invalide.

## Validation scientifique

annotations/conversations.json contient dix exemples de départ : bons, moyens
et mauvais, avec erreurs volontaires. human_scores reste null :
aucune annotation humaine n'est inventée. Ces exemples ne suffisent pas
à prouver la fiabilité.

Faire annoter 30 à 50 conversations par deux personnes indépendantes, avec
cette grille et les mêmes références. Ne pas révéler les notes IA aux annotateurs.
Apparier les notes par conversation_id, puis appeler :

```python
from app.quality.validation import agreement
report = agreement(human_scores, ai_scores)
```

Chaque élément est un dictionnaire des six notes. Le rapport donne Pearson
sur le total, erreur absolue moyenne et Cohen kappa non pondéré par critère.
None signifie que la statistique est indéfinie (notes constantes).
Comparer aussi les deux humains. La cible du PDF est kappa > 0,6 :
elle doit être mesurée, jamais annoncée sans données.
Le module ne persiste pas encore les évaluations : le backend doit stocker
scores/flags en JSONB, evaluator AI ou HUMAN, et la version du modèle/grille.

## Tests

```powershell
.\\.venv\\Scripts\\python.exe -m pytest tests/quality -q -p no:cacheprovider
```

Tests sans réseau : pondérations, score calculé, preuves inventées, références,
différence non-sourçage/invérifiable, erreurs HTTP et métriques d'accord.
Un essai avec un vrai modèle et la validation humaine restent nécessaires.

## Ollama lent sur CPU

Le delai local est configure a 600 secondes. Une generation complete peut
prendre plusieurs minutes avec qwen2.5:7b sur CPU. Redemarrer FastAPI apres
modification de .env. Ne pas relancer plusieurs evaluations simultanees.
504 = delai depasse ; 503 = connexion, modele absent ou erreur HTTP Ollama.
Les reponses 502, 503 et 504 sont declarees dans Swagger.
