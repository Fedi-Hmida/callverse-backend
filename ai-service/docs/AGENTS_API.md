# Appeler Workforce et Quality depuis Spring Boot

## Architecture

Frontend -> Spring Boot -> FastAPI (port 8000).
Spring Boot lit l'état du centre et les conversations depuis sa base, appelle
l'agent, stocke le résultat puis l'expose au frontend.
Workforce propose une réaffectation ; le backend vérifie la disponibilité,
les compétences et l'accord de la responsable avant de l'appliquer.
Quality analyse la conversation terminée ; le backend stocke l'évaluation.

## Lancement

Depuis la racine du projet :

```powershell
.\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Swagger : http://localhost:8000/docs
Contrats OpenAPI : http://localhost:8000/openapi.json
Si Spring Boot tourne dans Docker, localhost désigne son conteneur : utiliser
l'adresse du service FastAPI dans le réseau Docker.

Variables .env :
```dotenv
WORKFORCE_MODEL_PATH=workforce_policy.zip
QUALITY_OLLAMA_URL=http://localhost:11434
QUALITY_MODEL=nom-exact-du-modele-installe
QUALITY_TIMEOUT_SECONDS=600
```

## Endpoints

| Méthode | URL | Fonction |
|---|---|---|
| GET | /health | Service FastAPI vivant, sans vérifier les modèles |
| GET | /ai/workforce/capabilities | Files, unités et actions supportées |
| POST | /ai/workforce/decide | Proposition PPO par défaut |
| POST | /ai/workforce/decide?strategy=THRESHOLD&threshold_n=10 | Baseline à seuil |
| POST | /ai/workforce/decide?strategy=STATIC_FIFO | Affectation fixe |
| GET | /ai/quality/rubric | Les six critères et leurs poids |
| POST | /ai/quality/evaluate | Évaluation de la conversation |

## Workforce : requête

```json
{
  "queues": {"ACCOUNT": 20, "CARD": 3, "CREDIT": 1, "ADVISORY": 0},
  "avg_wait": {"ACCOUNT": 180, "CARD": 60, "CREDIT": 0, "ADVISORY": 0},
  "available": {"ACCOUNT": 0, "CARD": 2, "CREDIT": 1, "ADVISORY": 0},
  "sla_today": 0.75,
  "hour": 10,
  "trend": 1.0
}
```

Les trois dictionnaires doivent contenir exactement les quatre files.
queues = nombre de clients en attente ; avg_wait = attente moyenne en SECONDES ;
available = conseillers libres ; sla_today = ratio 0..1 ; hour = heure 0..23 ;
trend = indicateur d'arrivées (1 = neutre).
Le modèle reçoit les attentes en MINUTES après conversion.
L'ordre de ses 15 observations suit env.py.

Réponse déterministe pour la stratégie THRESHOLD sur cet exemple :

```json
{
  "type": "REASSIGN",
  "from_pool": "CARD",
  "to_pool": "ACCOUNT",
  "count": 1,
  "reason": "File ACCOUNT à 20 clients (seuil=10) — réaffectation depuis CARD.",
  "expected_gain": {},
  "strategy": "THRESHOLD",
  "action_id": 1,
  "requires_approval": true
}
```

La réponse est directement cet objet (sans enveloppe action).
PPO peut produire une autre décision. Aucune amélioration chiffrée n'est inventée :
expected_gain reste vide faute de modèle d'estimation.
La justification est construite à partir de l'état et de l'action :
elle n'est pas une explication du réseau neuronal.

Important : le modèle actuel a cinq actions, pas les huit actions du livret.
Il ne possède pas de file FRAUD. Ne pas convertir artificiellement FRAUD vers
une autre file pour réutiliser le modèle : modifier l'environnement et réentraîner.
PPO choisit la destination. La fonction partagee actions.select_donor choisit le premier pool disponible dans l ordre ACCOUNT, CARD, CREDIT, ADVISORY, dans le simulateur et l API. Le conseiller revient a sa competence d origine apres son appel. Voir WORKFORCE_CORRECTIONS.md pour les corrections et le nouveau modele.
Le modèle est chargé en mémoire et réutilisé ; redémarrer après remplacement du zip.

## Quality : requête

```json
{
  "conversation_id": "carte-001",
  "messages": [
    {"sender": "CUSTOMER", "content": "J'ai perdu ma carte."},
    {"sender": "ADVISOR", "content": "Je comprends. Utilisez le canal sécurisé pour faire opposition."}
  ],
  "source_documents": [
    {
      "doc_id": "carte",
      "chunk_id": "1",
      "content": "En cas de perte, orienter vers le canal sécurisé d'opposition."
    }
  ],
  "applicable_rules": ["En cas de perte, orienter vers le canal sécurisé d'opposition."],
  "resolved": false
}
```

advisor_response est optionnel : contrat existant answer, sources, tool_calls,
confidence. answer doit correspondre au dernier message ADVISOR.
Transmettre les résultats d'outils disponibles pour distinguer une action
annoncée d'une action exécutée. Ne pas envoyer uniquement les IDs de sources :
le juge a besoin du texte. Les règles sont celles de la banque fictive,
fournies par Spring Boot ou la KB.

La réponse contient :
- conversation_id, global_score (sur 10), scores (six notes sur 10).
- evidence : criterion, score, message_index, evidence et justification.
- explanation et recommendations.
- claims : affirmation citée, statut, références et justification.
- flags : unsourced_claims, unverifiable_claims, procedure_violations,
  missing_source_keys.

message_index commence à 0. Le frontend peut ouvrir la conversation directement
sur ce message. Les comptes dans flags sont calculés par Python à partir des
affirmations déclarées par le modèle ; leur exhaustivité n'est pas garantie.
Pour chaque critère, une seule preuve est demandée dans cette version.

## Client Java (Spring Framework 6.1+, exemple à adapter)

[Documentation officielle RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)

```java
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class CallVerseAiClient {
    private final RestClient client;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
        new ParameterizedTypeReference<>() {};

    public CallVerseAiClient(String baseUrl) {
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(660));
        this.client = RestClient.builder().baseUrl(baseUrl)
            .requestFactory(factory).build();
    }

    public Map<String, Object> decideWorkforce(Map<String, Object> state) {
        return client.post().uri("/ai/workforce/decide")
            .contentType(MediaType.APPLICATION_JSON).body(state)
            .retrieve().body(JSON);
    }

    public Map<String, Object> evaluateQuality(Map<String, Object> conversation) {
        return client.post().uri("/ai/quality/evaluate")
            .contentType(MediaType.APPLICATION_JSON).body(conversation)
            .retrieve().body(JSON);
    }
}
```

Configurer baseUrl avec http://localhost:8000.
Cet exemple n'est pas compilé dans ce dépôt Python. Utiliser ensuite des DTO
Java correspondant au contrat OpenAPI, avec les noms JSON snake_case.
RestClient lève une exception HTTP pour les réponses 4xx/5xx : la couche Spring
doit traduire ces erreurs pour le frontend, sans les convertir en note zéro.

## Erreurs et persistance

422 : données invalides ou stratégie inconnue.
503 : PPO absent/incompatible ou Ollama/modèle qualité indisponible.
502 : JSON du juge invalide ou extraits/références non vérifiables.

Les endpoints ne modifient pas le centre et n'écrivent pas en base.
Spring Boot stocke l'instantané d'état et la proposition Workforce ; après
validation humaine, il applique l'action et garde la trace.
Pour Quality, stocker la conversation et le résultat, evaluator=AI,
la version de grille et le modèle ; garder les annotations HUMAN séparément.
L'API n'inclut pas encore de CRUD ni de calcul NLP intention/sentiment.

504 : delai de generation Ollama depasse. Le client Spring utilise un delai
superieur au delai Ollama configure (660 s pour 600 s).
