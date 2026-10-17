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

### Agents encore non intégrés

- Le **Quality Analyst** n'est pas raccordé au flux live : il lui faut un
  déclencheur après conversation et une décision sur les données/traces
  d'évaluation.
- Le **Client Simulator** reste un outil d'expérimentation, pas un agent des
  conversations client réelles. Aucun raccordement live n'est ajouté.

---

## Prochaines étapes

- [x] Terminer le flux Advisor backend → ai-service et sa documentation.
- [x] Raccorder le Workforce en propositions supervisées uniquement.
- [ ] Définir séparément le déclencheur et les données du Quality Analyst avant son intégration.
- [ ] Garder le Simulator dans le cadre expérimental ; reconsidérer seulement avec un besoin d'orchestration explicite.
- [ ] Pousser `integration/backend-fedi` après revue ; aucun push n'a été effectué.
- [ ] Décider si un test HTTP réel sur Neon est nécessaire ; ne pas lire `.env` ni `ai-service/.env`.

---

**Dernière mise à jour :** 2026-10-17
