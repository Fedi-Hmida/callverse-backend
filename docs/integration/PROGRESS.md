# Intégration backend ↔ ai-service — Rapport d'avancement

**Branche :** `integration/backend-fedi`  
**Date de début :** 2026-10-15  
**Responsable technique :** Agent d'intégration (orchestrateur Kiro)

---

## Décisions actées (validées par l'équipe)

### Q1 — `apply_credit`
**Décision :** Option A — stub `501 Not Implemented` protégé par `X-Internal-Key`. Enveloppe d'erreur standard. Côté ai-service, `backend_client.py` traduit le 501 en résultat d'outil "indisponible". L'Advisor ne doit jamais annoncer un crédit accordé : il propose une escalade vers un conseiller.

### Q2 — Docker
**Décision :** À l'état initial, Docker n'était pas disponible. Docker répond dans WSL2 depuis le 2026-10-16 et `make test` a été exécuté. Les tests d'intégration `/internal` ne sont pas restaurés ni exécutés dans cette étape ; ils seront ajoutés avec le contrôleur `/internal`.

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
| Tests Python (contrats + tool_names_sync) | **7/7 ✓** (avec Python 3.12 + dépendances légères) |
| Tests Python (workforce/quality/simulator) | **Non exécutés** — dépendances numpy/gymnasium absentes (venv Linux non utilisable depuis PowerShell) |

---

## Étape 2 — Restauration sécurité `/internal` (sécurité restaurée ; routes différées)

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

### Tests de l'étape 2
- [x] `ServiceKeyVerifierTest.java` (unitaire, sans Spring) — 3/3 réussis.
- [ ] `AbstractInternalApiSecurityTest.java` + `InternalApiSecurityDevTest.java` + `InternalApiSecurityProdTest.java` — non restaurés et non exécutés dans ce commit ; ils seront restaurés avec le contrôleur `/internal`.

### Validations réellement exécutées (2026-10-16)

| Vérification | Résultat |
|---|---|
| `make build` | **BUILD SUCCESS** |
| `make arch` | **7/7 ✓** |
| `./mvnw test -Dtest=ServiceKeyVerifierTest` | **3/3 ✓** |
| `make test` | **403 tests, 3 échecs, 0 erreur, 0 ignoré** |
| `docker info` dans WSL2 | **Disponible** |

Échecs de `make test` :
- `OpenApiContractTest.committedDocumentMatches` — `openapi.yaml` n'est pas encore régénéré ; `make openapi` est à exécuter après le commit sécurité.
- `StompConversationTest.messagesBothWays` — échec constaté, cause non confirmée.
- `ConstraintEnforcementTest.queueQueryUsesItsIndex` — échec constaté, cause non confirmée.

Aucun test n'a été modifié.

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

## Commits réalisés

- `30ebb48 chore(git): suivre docs/integration/`

---

## Tests d'intégration `/internal` différés

Les tests suivants ne sont **pas restaurés et non exécutés** dans cette étape ; ils seront restaurés avec le contrôleur `/internal` :

- `AbstractInternalApiSecurityTest` (6 cas : bonne clé, pas de clé, mauvaise clé, JWT refusé, query string ignorée, CORS désactivé)
- `InternalApiSecurityDevTest` (vérification profil `dev`)
- `InternalApiSecurityProdTest` (vérification profil `prod`)
- Tous les tests d'intégration Spring à venir pour `/internal` (étape 3)

Docker répond dans WSL2 depuis le 2026-10-16 ; les tests pourront être exécutés une fois restaurés avec le contrôleur.

---

## Prochaines étapes

- [x] Compléter l'étape 2 (restauration de la chaîne et de sa configuration)
- [  ] Commit `feat(security): restaurer la chaîne service-key pour /internal`
- [  ] Après le commit sécurité : `make openapi`, puis commit séparé `chore(openapi): déclarer le schéma serviceKey`
- [  ] Étape 3 : `InternalToolController` + DTOs bancaires
- [  ] Étape 4 : corrections `backend_client.py`
- [  ] Étape 5 : adaptateur backend → ai-service (si modifications nécessaires)
- [  ] Étape 6 : test de bout en bout HTTP réel sur Neon perso

---

**Dernière mise à jour :** 2026-10-16
