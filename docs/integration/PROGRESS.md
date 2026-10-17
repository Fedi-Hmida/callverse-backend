# Intégration backend ↔ ai-service — Rapport d'avancement

**Branche :** `integration/backend-fedi`  
**Date de début :** 2026-10-15  
**Responsable technique :** Agent d'intégration (orchestrateur Kiro)

---

## Décisions actées (validées par l'équipe)

### Q1 — `apply_credit`
**Décision :** Option A — stub `501 Not Implemented` protégé par `X-Internal-Key`. Enveloppe d'erreur standard. Côté ai-service, `backend_client.py` traduit le 501 en résultat d'outil "indisponible". L'Advisor ne doit jamais annoncer un crédit accordé : il propose une escalade vers un conseiller.

### Q2 — Docker
**Décision :** Docker était indisponible au départ, puis est devenu disponible dans WSL2 le 2026-10-16. Les tests de sécurité `/internal` ont été restaurés avec le contrôleur et exécutés (13/13 réussis). Au dernier passage, `make test` compte 424 tests avec un échec dans la suite complète (`ConstraintEnforcementTest.queueQueryUsesItsIndex`), et `StompConversationTest.messagesBothWays` est intermittent (réussi au dernier passage).

### check_system_status — paramètre `service`
**Décision :** Le paramètre `zone` du LLM contient des services (`'paiement_carte'`, `'app_mobile'`), pas des régions. Ajout d'un paramètre optionnel `service` (enum `BankingService`) dans `GET /internal/service-incidents`. `TOOL_SPECS` garde le nom `zone`. `backend_client.py` convertit texte libre → enum (`paiement_carte` → `CARD_PAYMENTS`, `app_mobile` → `MOBILE_APP`, `virement` → `TRANSFERS`, etc.). Une valeur inconnue retourne un résultat explicite "service non reconnu" (jamais "aucun incident").

### incident_id dans create_case
**Décision :** Option C — aucun changement backend. `backend_client.py` ajoute "Incident lié : <id>" à la description du ticket. Mappe `subject` → `title`.

### Catégories de ticket
**Décision :** Utiliser le vocabulaire de `Intent.java` et `SkillCode.java` : `BALANCE`, `CARD`, `CREDIT`, `FRAUD`, `ACCOUNT_CLOSURE`, `ACCOUNTS`, `CARDS`, `OTHER`. Pas de migration (colonne `VARCHAR(30)` sans `CHECK`). La catégorie vient de l'intention suivie par l'Advisor, avec un repli explicite si absente.

### Préfixe `/internal`
**Décision :** Le client appelle `/api/v1/internal` (doc 01), le backend garde `/internal` (décision de Fedi, chaîne de sécurité dédiée). Utiliser `/internal`, rendre le préfixe configurable (variable d'environnement) et documenter l'écart avec le doc 01.

### POST /internal/tickets — conversationId
**Décision :** `conversationId` **OBLIGATOIRE** (400 `VALIDATION_FAILED` sinon). Le commentaire de `OpenTicketCommandHandler` demande de borner la confiance accordée à l'agent. Il est injecté par l'Advisor depuis son état, pas choisi par le LLM, et le backend vérifie qu'il appartient au client.

### get_customer — exclusions
**Décision :** IBAN masqué uniquement. Pas de `churn_risk`, flags de simulation ni contacts. Corriger la description dans `TOOL_SPECS` ("contrat, forfait, risque de churn" → "comptes, cartes, segment, ancienneté"). Chercher et corriger dans ai-service tout ce qui lit `churn`, `contracts` ou `plan`.

### escalate — description OpenAPI
**Décision :** Mettre à jour la description OpenAPI (la conversation passe maintenant en `ESCALATED`).

### Alignement variable d'environnement
**Décision :** Aligner le nom de la variable d'environnement d'URL du backend entre `docker-compose.yml` (`CALLVERSE_BACKEND_BASE_URL`) et `ai-service/app/config.py`.

---

## Étape 1 — Vérification de base (READ-ONLY) ✓

| Vérification | Résultat |
|---|---|
| Branche courante | `integration/backend-fedi` ✓ |
| `make arch` (7 règles ArchUnit) | **7/7 ✓** |
| `make build -DskipTests` | **BUILD SUCCESS ✓** |
| Docker | **Disponible dans WSL2 depuis le 2026-10-16** — `make test` exécuté (voir validations ci-dessous) |
| Tests Python ciblés (client backend, contrats d'outils, graphe Advisor) | **19/19 ✓** (Python 3.12 sous WSL2) |
| Tests Python automatisés (workforce/quality/simulator inclus) | **90/90 ✓** ; hors test LLM réel et script d'entraînement |

---

## Étape 2 — Restauration sécurité `/internal` ✓

### Fichiers à restaurer depuis le tag (identiques)
- [x] `infrastructure/security/ServiceKeyVerifier.java`
- [x] `infrastructure/security/ServiceKeyAuthenticationFilter.java`
- [x] `infrastructure/security/InternalApiSecurityConfiguration.java`
- [x] `host/api/errorhandling/ServiceKeyAuthenticationEntryPoint.java`

### Fichiers à modifier
- [x] `application.yml` — ajouter bloc `internal.service-key`
- [x] `.env.example` — ajouter `INTERNAL_SERVICE_KEY` (placeholder)
- [x] `docker-compose.yml` — ajouter variable dans backend + ai-service (`${INTERNAL_SERVICE_KEY:?set INTERNAL_SERVICE_KEY in .env}`)
- [x] `OpenApiConfiguration.java` — ajouter scheme `serviceKey` dans l'API live
- [x] `AbstractPersistenceTest.java` — ajouter `TEST_INTERNAL_SERVICE_KEY` factice + `registry.add`
- [x] `AuthenticatedRequests.java` — ajouter `serviceKey()` / `serviceKey(String)`
- [x] `InternalApiSecurityConfiguration.java` — valider `internal.api-prefix` avant `securityMatcher` et refuser les préfixes `/api` et `/api/v1`

### Tests de l'étape 2
- [x] `ServiceKeyVerifierTest.java` (unitaire, sans Spring) — 3/3 réussis.
- [x] `AbstractInternalApiSecurityTest.java` + `InternalApiSecurityDevTest.java` + `InternalApiSecurityProdTest.java` — restaurés avec le contrôleur ; tests dev/prod 13/13 réussis.

### Validations réellement exécutées (2026-10-16)

| Vérification | Résultat |
|---|---|
| `make build` | **BUILD SUCCESS** |
| `make arch` | **7/7 ✓** |
| `./mvnw test -Dtest=ServiceKeyVerifierTest` | **3/3 ✓** |
| `./mvnw test -Dtest=InternalApiSecurityConfigurationTest` | **2/2 ✓** |
| Tests `InternalApiSecurityDevTest,InternalApiSecurityProdTest` | **13/13 ✓** |
| `make openapi` | **BUILD SUCCESS ; OpenApiContractTest 3/3 ✓** |
| Tests Python ciblés (client backend, contrat d'outils, graphe Advisor) | **19/19 ✓** |
| Suite pytest Python automatisée | **90/90 ✓** (Python 3.12 ; test LLM réel et script d'entraînement exclus) |
| Tests `/internal` (sécurité dev/prod + six routes) | **19/19 ✓** |
| Dernier `make test` complet | **424 tests, 1 échec, 0 erreur, 0 ignoré** |
| `docker info` dans WSL2 | **Disponible** |

Échec du dernier `make test` complet, hors sécurité `/internal` :
- `ConstraintEnforcementTest.queueQueryUsesItsIndex` — le plan de requête ne choisit pas l'index sur une table d'une ligne. Le test passe seul sur `origin/main` et sur cette branche, mais échoue dans la suite complète de cette branche. La suite complète d'`origin/main` n'a PAS été exécutée ; sa vérification revient à Fedi. L'échec était déjà présent avant l'ajout des tests `/internal`.

`OpenApiContractTest` passe maintenant après génération du document et ajout des six nouveaux `operationId` épinglés.
`StompConversationTest.messagesBothWays` est intermittent : passé au dernier passage, échoué lors de passages précédents. Ces tests n'ont pas été modifiés.

---

## Écarts avec le doc 01 et le tag

### Écart 1 — Préfixe d'API
- **Doc 01 :** `/api/v1/internal/*`
- **Tag :** `/internal/*`
- **Décision Fedi :** Garder `/internal/*` (chaîne de sécurité dédiée `@Order(1)`)
- **Impact :** `backend_client.py` doit utiliser `/internal` et non `/api/v1/internal`

### Écart 2 — check_system_status paramètre `zone`
- **TOOL_SPECS :** paramètre `zone` contient des services (`'paiement_carte'`, `'app_mobile'`)
- **Backend :** ajout d'un paramètre optionnel `service` (enum `BankingService`)
- **Conversion :** `backend_client.py` mappe texte libre → enum
- **Gestion erreur :** valeur inconnue → résultat explicite "service non reconnu"

### Écart 3 — create_case incident_id
- **TOOL_SPECS :** paramètre optionnel `incident_id`
- **Backend :** aucun champ correspondant dans `OpenTicketCommand`
- **Solution :** `backend_client.py` ajoute "Incident lié : <id>" à la description

### Écart 4 — conversationId obligatoire
- **Tag :** `conversationId` optionnel dans `OpenTicketRequest`
- **Nouvelle règle :** `conversationId` **OBLIGATOIRE** pour `/internal/tickets` (borner la confiance accordée à l'agent)

---

## Commits déjà présents

- `30ebb48 chore(git): suivre docs/integration/`
- `258fc84 feat(security): restaurer la chaîne service-key pour /internal`
- `379b22e chore(openapi): déclarer le schéma serviceKey`

## Commits de finalisation

- Routes backend et tests : `feat(api): add internal banking routes`
- Validation du préfixe : `fix(security): validate internal API prefix`
- Client ai-service : `feat(ai): connect advisor tools to internal backend`
- OpenAPI : `chore(openapi): document internal banking routes`
- Rapport : `docs(integration): update integration progress`
---

## Étape 3 — Routes `/internal` et alignement ai-service

Les six opérations sont présentes dans `InternalToolController` et publiées dans `openapi.yaml` :

- `GET /internal/customers/{id}`
- `GET /internal/customers/{id}/transactions`
- `GET /internal/service-incidents`
- `POST /internal/tickets`
- `POST /internal/conversations/{id}/escalate`
- `POST /internal/credits` (stub `501 NOT_IMPLEMENTED`)

Le client Python appelle ces chemins avec `X-Internal-Key`, convertit `zone` en enum `service`, transmet le `conversation_id` de l'état Advisor pour les tickets et traite le 501 comme une indisponibilité explicite. La description de `get_customer` est alignée sur les données réellement exposées.

Les six routes ont chacune un test fonctionnel ciblé, exécuté avec Docker. Le test client vérifie la fiche et le masquage d'IBAN ; le test transactions vérifie les champs libres masqués ; le test incidents vérifie le filtre service ; le test ticket vérifie création, lien conversation/client et valeurs par défaut ; le test escalade vérifie la transition et l'idempotence ; le test crédit vérifie le 501 et l'enveloppe. Les IBAN des fixtures sont uniques pour permettre l'exécution de la suite complète.

`openapi.yaml` a reçu uniquement les six opérations `/internal`, le tag associé et les schémas nécessaires. `OpenApiContractTest` épingle aussi les six nouveaux identifiants.

---

## Étape 4 — Flux Advisor backend → ai-service (2026-10-17)

### Étape 1 — Backend ✓

Commit : `fb5f4b4 feat(advisor): integrate async customer advisor turn`.

- L'Advisor est appelé après commit du message client, sur un exécuteur borné ; une seule réponse est en vol par conversation.
- Seuls les états `ASSIGNED` et `ACTIVE` sont traités ; `QUEUED` et `ESCALATED` sont ignorés.
- L'historique est limité à 20 messages antérieurs, hors `SYSTEM`. Le contexte envoyé se limite à l'ancienneté et au segment ; `open_tickets` n'est pas fourni par un port existant.
- La réponse est enregistrée comme tour `ADVISOR` avec `ai_generated=true`. Les traces utilisent les colonnes existantes et ne sont pas exposées dans les routes publiques ni les événements temps réel.
- L'état et le dernier message sont revérifiés sous verrou avant l'écriture. Les erreurs n'entraînent pas d'escalade automatique.

### Étape 2 — ai-service ✓

Commit : `3e6ca60 feat(ai): secure advisor endpoint and align context`.

- `contract_type`, `churn_risk` et `open_tickets` sont optionnels pour compatibilité ; `segment` est accepté.
- Les prompts et nœuds Advisor n'utilisent plus `contract_type` ni `churn_risk`.
- `AI_SERVICE_KEY`, si défini, protège les routes `/ai/*` avec `X-AI-Key`. `/health` reste public ; une clé vide désactive la protection.
- Compose fournit la même variable au backend et à l'ai-service. Sa valeur reste une configuration locale et n'est pas suivie par Git.

### Étape 3 — Documentation ✓

Le flux, sa configuration et ses limites sont décrits dans
[ADVISOR_FLOW.md](./ADVISOR_FLOW.md). Les traces restent non exposées en
attendant la confirmation de la règle A9.

### Validations réellement exécutées

| Vérification | Résultat |
|---|---|
| Compilation backend et tests (`-DskipTests test-compile`) | **BUILD SUCCESS** |
| Tests unitaires Advisor backend | **5/5 ✓** |
| Test d'intégration Advisor avec Testcontainers (WSL2) | **1/1 ✓** |
| `make arch` | **7/7 ✓** |
| Tests ciblés Advisor, contrat et authentification Python (`pytest tests/advisor tests/test_ai_service_auth.py`) | **24/24 ✓** |
| Suite automatisée Python (`pytest tests`) | **95 collectés : 93 réussis, 2 échecs** |
| `pytest` sans chemin | **Échec de collecte de deux scripts manuels sous `scripts/`** |

Les deux échecs dans `pytest tests` concernent le Simulator, pas l'Advisor :
`test_graph_manual.py::test_reponse_utile_ameliore_satisfaction` et
`test_router_mocked.py::test_next_message_endpoint_conforme_au_contrat`.
Ils échouent parce qu'ils tentent d'utiliser Groq sans `GROQ_API_KEY`
configurée. L'invocation sans chemin collecte également
`scripts/test_advisor_reformulation.py` et
`scripts/test_simulator_multi_tours.py`, qui appellent le LLM au chargement et
échouent pour la même absence de configuration. Ces résultats sont rapportés,
pas masqués ; aucun test existant n'a été modifié.

### Limites et suites

- Les conversations `QUEUED` ne sont pas traitées par l'Advisor.
- Les `sources` et `tool_calls` sont persistés, mais non exposés ; la règle A9
  n'est pas confirmée.
- Le contexte envoyé est réduit aux données backend déjà disponibles.
- Aucun push n'a été effectué. Le push de `integration/backend-fedi` reste à
  faire après revue par l'équipe.

---

## Étape 5 — Workforce Manager (propositions supervisées)

### Alignement Python et modèle ✓

- Les quatre compétences du contrat Workforce sont alignées sur Spring :
  `ACCOUNTS`, `CARDS`, `CREDIT`, `FRAUD`. L'ordre des observations et des actions
  correspond à cet ordre canonique.
- Commit modèle/contrat : `a7c89b7 feat(workforce): aligner les compétences et sélectionner le modèle PPO`.
- PPO a été entraîné sur 100 000 pas (graine 7) ; le meilleur checkpoint
  d'évaluation à charge moyenne a été sélectionné (50 000 pas) et est le modèle
  par défaut.
- L'évaluation synthétique sur cinq graines par charge montre une meilleure SLA
  que les deux baselines aux trois charges, mais davantage d'abandons en charge
  faible et moyenne. Cette limite interdit de traiter le modèle comme une policy
  de production automatique ; détails dans
  [WORKFORCE_FLOW.md](./WORKFORCE_FLOW.md).

### Coordination Spring ↔ ai-service ✓

- Commit backend : `68eeed5 feat(workforce): connecter le manager aux opérations live`.
- `POST /api/v1/supervision/workforce/recommendations` est autorisé seulement aux
  superviseurs et administrateurs. Il appelle `POST /ai/workforce/decide` avec
  `X-AI-Key`, renvoie l'observation et la proposition, puis la journalise dans
  `agent_decision`.
- La ligne est de type `WORKFORCE_MANAGER`, en mode live (`run_id` et
  `sim_time` nuls), avec `approved_by` nul. Aucune affectation n'est modifiée ;
  aucun endpoint d'application n'a été ajouté.
- Les files et attentes courantes, capacités restantes et SLA mesurée viennent
  du backend. Les conseillers multiskill peuvent être comptés dans plusieurs
  pools. La tendance est neutre (`1.0`), et le SLA utilise `1.0` explicitement
  signalé si aucune mesure n'existe.
- Aucune migration Flyway n'a été créée ou modifiée. L'API générée décrit la
  nouvelle route et son schéma de réponse.

### Validations Workforce réellement exécutées

| Vérification | Résultat |
|---|---|
| `pytest tests/workforce -q` (WSL, `~/venv-ai`) | **18/18 ✓** |
| Chargement du modèle par le chemin par défaut | **15 observations, 5 actions ✓** |
| Tests ciblés Spring (use case, HTTP, Testcontainers + accès) | **7/7 ✓** |
| `make arch` | **7/7 ✓** |
| `make openapi` / `OpenApiContractTest` | **3/3 ✓** |
| `make build` | **BUILD SUCCESS ✓** |
| `make test` complet | **437 tests, 1 échec, 0 erreur, 0 ignoré** |

L'unique échec complet est `ConstraintEnforcementTest.queueQueryUsesItsIndex` :
le plan PostgreSQL ne choisit pas l'index sur une table de test d'une ligne. Il
avait déjà été observé avant Workforce ; Fedi a confirmé qu'il passe seul sur
`origin/main` et sur cette branche, mais la cause de l'échec dans la suite
complète reste non confirmée. `StompConversationTest.messagesBothWays`,
intermittent aux passages précédents, a réussi lors de cette exécution. Aucun
de ces deux tests n'a été modifié.

## Étape 6 — Quality Analyst (évaluation asynchrone à la résolution)

- Le changement `RESOLVED`, publié après commit, déclenche le cas d'usage sur
  un exécuteur borné. Les conversations abandonnées, non résolues, déjà évaluées
  par l'IA ou sans transcript admissible sont ignorées.
- Le backend envoie au Quality Analyst au plus 500 messages `CUSTOMER` /
  `ADVISOR`, sans les messages `SYSTEM`, `sources`, `tool_calls` ni les détails
  privés de l'Advisor. `source_documents` et `applicable_rules` sont vides ;
  le contexte de notation est donc réduit et A9 reste non confirmée.
- Le résultat est enregistré dans les colonnes existantes de
  `quality_evaluation` (`global_score`, `scores`, `explanation`, `flags`,
  `evaluator=AI`). Les six clés de score sont converties en majuscules pour
  correspondre aux codes de `quality_criterion`. Preuves, recommandations et
  claims ne sont pas persistés car V1 ne fournit pas ces colonnes.
- L'échec du service, une réponse invalide ou le rejet de l'exécuteur produit
  un avertissement sans secret et n'affecte pas la résolution. Aucun endpoint
  de lecture UI n'est ajouté.
- Aucun fichier de migration n'a été créé ou modifié.
- Détails : [QUALITY_FLOW.md](./QUALITY_FLOW.md).

### Client Simulator

- Le Client Simulator est raccordé dans un flux isolé `Simulator → Advisor` :
  `POST /api/v1/simulation-runs` (superviseur/admin) crée un client marqué
  simulé, une conversation liée à `simulation_run`, puis planifie le dialogue
  sur un exécuteur borné ; `GET /api/v1/simulation-runs/{runId}` permet le suivi.
- Les paramètres du dialogue sont explicites (scénario, stratégie, seed, profil,
  objectif, patience, satisfaction, segment, ancienneté et plafond de tours,
  limité à 50). La contrainte existante `(scenario_id, strategy_id, seed)`
  interdit de relancer le même triplet.
- En mode `SIMULATION`, l'Advisor ne peut exécuter que la recherche documentaire ;
  aucun outil métier backend, événement temps réel ou conversation live n'est
  utilisé. Les agents reçoivent les 20 messages les plus récents, hors `SYSTEM`.
- Les messages, la réponse Advisor (`ai_generated=true`) et les traces utilisent
  uniquement les colonnes existantes. Les échecs/timeout/réponses invalides et la
  limite atteinte marquent le run `FAILED` avec un message générique. Aucun schéma
  ni migration n'a été modifié.
- Détails et limites : [SIMULATOR_FLOW.md](./SIMULATOR_FLOW.md). Les tests à mocks
  et Testcontainers ne constituent pas un appel réel à un LLM.

### Validations Simulator

| Vérification | Résultat |
|---|---|
| `pytest tests/advisor/test_advisor_graph.py -q` (WSL, `~/venv-ai`) | **13/13 ✓** |
| Tests Spring ciblés (orchestration, HTTP Simulator/Advisor, dispatcher) | **10/10 ✓** |
| Persistance Simulator Testcontainers PostgreSQL | **1/1 ✓** |
| API Simulator, autorisations et suivi Testcontainers | **1/1 ✓** |
| `make openapi` / `OpenApiContractTest` | **réussi** |
| `make arch` | **7/7 ✓** |
| `make test` complet | **458 tests, 1 échec, 0 erreur, 0 ignoré** |
| Appel réel Simulator/Advisor avec LLM | **non exécuté** |

L'unique échec de `make test` est `ConstraintEnforcementTest.queueQueryUsesItsIndex` :
le plan PostgreSQL ne choisit pas l'index sur une table de test d'une ligne.
Ce test passe seul et l'échec est déjà connu, sans lien confirmé avec Simulator.

### Validations Quality ciblées

| Vérification | Résultat |
|---|---|
| Tests HTTP Quality, use case, déclenchement après commit, mapping persistance | **9/9 ✓** |
| `LayerDependencyTest` (équivalent à `make arch`) | **7/7 ✓** |
| Suite Spring complète (`make test` dans WSL) | **446 tests, 1 échec connu, 0 erreur** |

L'unique échec, `ConstraintEnforcementTest.queueQueryUsesItsIndex`, est le
problème déjà suivi : le plan de requête ne choisit pas l'index sur une table
de test d'une ligne ; le test passe seul. `StompConversationTest` a réussi
5/5. Aucun test existant n'a été modifié.

---

## Étape 7 — Phase P0 de l'audit (2026-10-17)

Suite de l'audit d'intégration du 2026-10-17 (note 6,2/10, « non prêt »). P0.1
(fichier `.env`) est traité hors de cette branche par décision de Fedi.

| Point | Problème mesuré à l'audit | Correction | Preuve |
|---|---|---|---|
| P0.2 | L'IA répondait à chaque message client, même quand un conseiller humain écrivait : deux réponses (scénario S1g) | `ai.advisor.mode` = `OFF` (défaut) ou `AUTONOMOUS` ; en `AUTONOMOUS`, l'IA se tait dès qu'un humain a écrit (reprise par l'humain). Règle dans le cœur (`AiAdvisorMode`, `AdvisorTurnCommandHandler`), requête `humanAdvisorHasReplied` | Tests unitaires et Testcontainers ; S1g en direct : 1 seul tour IA après l'intervention de Karim ; mode par défaut : 0 appel à l'IA |
| P0.3 | Le second message rapide du client restait sans réponse 9 fois sur 10 (S1h) | `AdvisorTurnDispatcher` regroupe au lieu d'abandonner : un message en attente par conversation (le plus récent), un tour par tâche puis remise en file (équité entre conversations), sans réveil perdu ; un rejet de l'exécuteur garde le message en attente | 9 tests dont un test de concurrence réelle (50 conversations × 20 messages) et un test d'équité ; S1h en direct : 10/10 |
| P0.4 | Le LLM choisissait `customer_id` et `conversation_id` (lecture ou escalade d'un autre client possible) | `TRUSTED_IDENTIFIERS` injecte les identifiants depuis la requête ; retirés des schémas d'outils et du prompt | 7 tests pytest dont une tentative d'injection |
| P0.5 | `/ai/*` ouvert quand `AI_SERVICE_KEY` était vide (valeur par défaut) | Démarrage refusé sans clé ; opt-out explicite `AI_SERVICE_AUTH_DISABLED=true` pour le développement ; compose exige la clé pour l'ai-service | 4 tests pytest |
| P0.6 | 14 commits portent un `Co-authored-by` d'outil | Pas de réécriture d'historique sur cette branche partagée : à traiter au moment de la fusion dans `main` (squash) | Les nouveaux commits n'en portent aucun |

Tests modifiés volontairement (comportement changé, non affaiblis) :
`test_ai_routes_remain_compatible_when_key_is_unconfigured` devient le cas
fermé ; le test `create_case` attend l'identifiant client de confiance (et
reçoit le `mode` manquant qui le faisait échouer) ; dans
`AdvisorTurnIntegrationTest`, la salutation initiale devient un tour IA, car une
salutation humaine signifie désormais la reprise par l'humain.

### Validations P0 réellement exécutées

| Vérification | Résultat |
|---|---|
| `mvnw clean verify` (Testcontainers) | **470 tests, 0 échec** (458 → 470) |
| `pytest tests/advisor tests/test_ai_service_auth.py tests/test_backend_client_contract.py tests/quality tests/simulator` | **84 réussis, 2 échecs connus** (Groq sans clé, identiques sur le code d'origine) |
| Mutations des 10 nouvelles règles | **10/10 détectées** |
| Scénarios en direct (backend + Postgres jetable + maquette IA) | S1g corrigé, S1h 10/10, mode par défaut silencieux |
| Revue indépendante | 1 point important corrigé (équité de l'exécuteur), compose vérifié (`docker compose config` sans profil `ai`), 5 points mineurs corrigés |

Environnement Python : venv local sans torch ; `sentence_transformers` remplacé
par un module factice hors dépôt, uniquement pour l'import (les tests
concernés n'encodent rien). Les tests workforce n'ont pas été exécutés ici.

---

## Étape 8 — Phase P1 de l'audit (2026-10-18)

| Point | Problème mesuré | Correction | Preuve |
|---|---|---|---|
| P1.1 | « Je transmets à un superviseur » sans escalade réelle | L'action `ESCALATE` devient une vraie escalade (`raised_by = AI`) après publication de la réponse ; l'outil `escalate`, qui faisait perdre la réponse, est retiré | Tests unitaires et Testcontainers ; en direct : conversation `ESCALATED`, alerte et liste superviseur |
| P1.2 | IBAN brut envoyé au LLM externe | `PersonalDataRedactor` (cœur) : IBAN toutes formes (mod 97), carte (Luhn), e-mail, téléphone, avant l'Advisor et le Quality Analyst | 8 cas de masquage ; en direct : le LLM reçoit `FR76 **** **** 0189`, la base garde l'original |
| P1.3 | Délais incohérents (30 s Java, 120 s juge, Advisor sans échéance) | Échéance 25 s par requête Advisor/Simulator (504), lecture Java Quality 130 s, erreurs sans détail, pool dédié au Simulator, fin de tour Simulator conforme au contrat | 7 tests pytest |
| P1.4 | Notes inventées (5 par défaut), juge sans contexte | Sortie incomplète rejetée (502), affirmation mal située écartée ; le backend envoie les articles cités par l'IA (10 au plus, 1 500 caractères) et les 5 règles de la banque (livret § 3.3) | Tests pytest, unitaires et Testcontainers ; en direct : 1 article et 5 règles reçus |
| P1.8 | Ni Dockerfile ni image | Dockerfile CPU, utilisateur non root, `.dockerignore`, compose construit l'image, `env_file` optionnel, volume du modèle d'embeddings, workflow GitHub Actions | Image construite ; sans clé : refus de démarrer ; avec clé : `/health` 200, `/ai/*` 401 sans clé, décision PPO réelle dans le conteneur |
| P1.9 | Test de plan d'index instable dans la suite complète | `enable_seqscan = off` + statistiques fraîches ; vérifie une recherche par index sur `status` et `skill_id` | Échec déterministe reproduit (3/3), puis vert 3/3 ; un mutant de requête est détecté |
| P1.10 | `pytest` sans argument interrompu ; test e2e obsolète | `testpaths = tests`, marqueur `llm` exclu par défaut, test « mocked » rendu réellement sans LLM, compétences bancaires dans l'e2e | `pytest` : 122 réussis, 1 désélectionné (`-m llm`) |

Non traités ici, et pourquoi : **P1.5** (rejet d'une proposition Workforce : demande
une évolution du schéma, décision du propriétaire du schéma) ; **P1.6** (démonstration
avec un vrai LLM : demande une clé d'équipe) ; **P1.7** (évaluation PPO banking_v1 :
environnement d'entraînement d'Ines). Restent à prévoir : relance d'une évaluation
qualité rejetée, nom du client et libellés de transactions vers le LLM, versions des
dépendances figées.

### Validations P1 réellement exécutées

| Vérification | Résultat |
|---|---|
| `mvnw clean verify` (Testcontainers) | **491 tests, 0 échec** (470 → 491) |
| `pytest` (sans argument) | **122 réussis**, 1 test `llm` désélectionné |
| Mutations des règles P1 | **19/19 détectées** |
| Scénarios en direct (backend + Postgres jetable + maquette IA) | escalade, masquage, contexte qualité : 6/6 |
| Image Docker | construite et testée (refus sans clé, 401, décision PPO) |
| Revue indépendante | 1 point critique corrigé (`NameError` dans la réparation des affirmations), 5 points importants dont 4 corrigés et 1 en partie (relance des évaluations rejetées prévue en P2), mineurs corrigés ou documentés |

---

## Prochaines étapes

- [x] Terminer le flux Advisor backend → ai-service et sa documentation.
- [x] Raccorder le Workforce en propositions supervisées uniquement.
- [x] Raccorder le Quality Analyst après résolution, avec contexte réduit et persistance existante.
- [x] Raccorder le Client Simulator à l'Advisor dans des runs explicitement simulés.
- [x] Phase P0 de l'audit (P0.2 à P0.5) ; P0.6 au moment de la fusion.
- [x] Phase P1 de l'audit, sauf P1.5 (schéma), P1.6 (clé LLM) et P1.7 (entraînement).
- [ ] P1.5 : validation ou rejet des propositions Workforce (décision du propriétaire du schéma).
- [ ] P1.6 : une démonstration enregistrée avec un vrai LLM (clé d'équipe, hors Git).
- [ ] P1.7 : évaluation PPO banking_v1 commitée (au moins 3 graines).
- [ ] Fusionner dans `main` par squash, sans mention d'outil dans l'historique.
- [ ] Décider si un test HTTP réel sur Neon est nécessaire ; ne pas lire `.env` ni `ai-service/.env`.

---

**Dernière mise à jour :** 2026-10-17
