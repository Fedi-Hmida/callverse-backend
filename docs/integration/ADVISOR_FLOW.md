# Flux Advisor : backend CallVerse → ai-service

## Vue d'ensemble

Lorsqu'un client publie un message dans une conversation, le backend déclenche
l'Advisor Python après la validation du message en base. L'appel au modèle se
fait en arrière-plan : la requête HTTP du client n'attend pas le LLM et aucun
verrou de conversation n'est conservé pendant l'appel réseau.

```text
Client
  └─ POST /api/v1/conversations/{id}/messages
       └─ backend persiste le message client et valide la transaction
            └─ publication MESSAGE_POSTED
                 └─ si AI_ADVISOR_MODE=AUTONOMOUS : exécuteur borné, une tâche en cours
                    par conversation, messages arrivés entre-temps regroupés sur le dernier
                      └─ POST {AI_SERVICE_BASE_URL}/ai/advisor/respond
                           └─ ai-service appelle l'Advisor et ses outils /internal
                                └─ backend persiste le tour Advisor
                                     └─ événement temps réel du message
```

Seuls les messages `CUSTOMER` déclenchent ce flux. En `AUTONOMOUS`, toutes les
conversations ouvertes sont prises en charge : `QUEUED`, `ASSIGNED`, `ACTIVE` et
`ESCALATED`. Une réponse IA laisse un contact en attente dans sa file et ne
supprime pas une escalade. Les conversations terminées restent en lecture seule.

## Mode de l’Advisor et participation humaine

`AI_ADVISOR_MODE=AUTONOMOUS` est activé dans la configuration locale et proposé
dans `.env.example`. Sans configuration explicite, le backend conserve `OFF`.
L’IA répond en attente, après affectation et pendant une escalade, jusqu’à la
première réponse d’un conseiller humain. Lire, affecter ou escalader la demande
ne l’arrête pas. Après cette réponse humaine, les prochains messages clients sont
laissés au conseiller. Le verrou vérifie également cette reprise avant d’enregistrer
une réponse IA : un calcul déjà lancé ne peut pas parler après l’intervention humaine.

Un superviseur peut répondre dans une conversation `ESCALATED`, même sans
conseiller affecté. Cette réponse est enregistrée comme un message humain
`ADVISOR`, visible au client, et arrête l’IA comme celle du conseiller affecté.
Elle ne résout pas l’escalade : la résolution reste une action explicite du superviseur.
Hors escalade, ses conversations restent en lecture seule.

Si le client envoie un second message pendant qu'un tour est en cours, il n'est
plus perdu : le message est mis en attente et traité dès la fin du tour en cours,
en ne gardant que le plus récent (l'historique contient les précédents).

## Contrat HTTP Advisor

La requête `POST /ai/advisor/respond` contient :

- `conversation_id`, `customer_id`, `message` et `mode` (`LIVE`) ;
- `history`, au plus 20 messages précédents, sans `SYSTEM`, avec les rôles
  `customer`/`advisor` et leur horodatage `ts` ;
- `context.tenure_months` et `context.segment`, les seules données client
  utilisées par le backend dans ce flux. `open_tickets` n'est pas envoyé,
  aucun port existant ne fournissant cette donnée.

Le contrat Python accepte aussi les anciens champs `contract_type`,
`churn_risk` et `open_tickets` pour rester compatible avec les anciens
consommateurs, mais ils sont optionnels. Les prompts et nœuds Advisor ne
dépendent ni de `contract_type` ni de `churn_risk`.

La réponse contient `reply`, `intent`, `confidence`, `tool_calls`, `sources`,
`action` et `latency_ms`. Le backend vérifie les champs requis avant toute
persistance.

## Authentification et configuration

`AI_SERVICE_BASE_URL` configure l'URL de base utilisée par le backend. Le
client envoie `AI_SERVICE_KEY` dans l'en-tête `X-AI-Key`. Le délai de connexion
est de 2 secondes et le délai de lecture de 30 secondes.

L'ai-service exige `AI_SERVICE_KEY` sur les routes `/ai/*`, sous l'en-tête
`X-AI-Key`. `/health` reste accessible sans clé. **Sans clé, le service refuse
de démarrer** (fail closed) ; seul `AI_SERVICE_AUTH_DISABLED=true`, réservé au
développement local, le laisse démarrer avec des routes ouvertes. Configurez la
même clé dans l'environnement du backend et de l'ai-service ; ne placez jamais
sa valeur dans un fichier suivi par Git. `INTERNAL_SERVICE_KEY` est une clé
distincte utilisée dans l'autre sens, pour protéger les appels outils de
l'ai-service vers `/internal/**`.

Les outils reçoivent `customer_id` et `conversation_id` **de la requête du
backend, jamais du LLM** (`TRUSTED_IDENTIFIERS` dans `app/advisor/nodes.py`) ;
ces champs ne figurent plus dans les schémas d'outils. Un message client
malveillant ne peut donc pas orienter un outil vers un autre client.

## Persistance, diffusion et comportement dégradé

Le backend enregistre la réponse comme un message `ADVISOR` avec
`ai_generated=true`. Les `sources` et `tool_calls` sont stockés dans les
colonnes existantes de la table `message`. Ils ne sont pas ajoutés au
`MessageRecord`, aux routes publiques ou aux événements temps réel ; leur
exposition reste bloquée en attendant la confirmation de la règle A9.

Avant d'enregistrer la réponse, le backend reprend le verrou de la conversation
et vérifie que le client n'a pas envoyé un message plus récent et que l'état
permet toujours un tour Advisor. Une réponse devenue obsolète est abandonnée.
Un échec réseau, un quota fournisseur, un délai dépassé ou une réponse invalide
produit un message honnête d’indisponibilité et une escalade pour reprise humaine.
Une saturation de l’exécuteur conserve le message en attente ; la fin d’un autre
traitement le reprogramme sans exiger un nouvel envoi du client.

Le client affiche une réponse en préparation et relit temporairement le transcript
en attendant. Cela permet de recevoir la réponse même si le flux WebSocket est
interrompu, sans recharger la page. Après une attente prolongée, une commande
permet d’actualiser l’échange.

## Escalade demandée par l'IA

Quand l’Advisor renvoie `action.type = ESCALATE`, le backend publie d’abord la
réponse puis crée une escalade réelle. Un contact `QUEUED` peut être transmis
directement à la supervision par l’IA, sans inventer une attribution à un
conseiller. La liste d’attente et les indicateurs sont actualisés. Une escalade
déjà en attente est réutilisée ; elle n’est pas dupliquée.

## Données personnelles et délais

Avant tout appel au modèle, le message et l'historique sont masqués
(`PersonalDataRedactor`) : IBAN sous toutes ses formes (groupes de 4, groupes du
RIB, tirets, espace après le code pays ; contrôle ISO 13616 mod 97), numéro de
carte valide (contrôle de Luhn, même collé à un autre nombre), e-mail et
téléphone (0X, +33, 0033, +33 (0)). La base garde l'original.

L'ai-service borne chaque requête Advisor ou Simulator à
`ai_request_deadline_seconds` (25 s par défaut) et répond 504 au-delà, avant le
délai de lecture de 30 s du backend. Une erreur interne renvoie un message
générique ; son détail reste dans les journaux. Le Simulator tourne dans un pool
de threads dédié et borné (4), pour ne pas priver l'Advisor de threads. Un outil
déjà lancé quand l'échéance tombe (ticket, geste commercial) peut s'exécuter
côté backend sans qu'aucune réponse ne soit publiée, comme avant avec le délai
de 30 s du backend.

## Budget d'outils (constaté sur Groq réel, 2026-10-18)

Un message client donne au plus `MAX_ITERATIONS` (4) tours de décision. Le
dernier est réservé à la conclusion : le modèle n'y voit que `finalize_response`,
imposé, avec ce qu'il a déjà obtenu des outils. Avant, pour une carte refusée, le
modèle enchaînait quatre outils (base documentaire, client, état du système,
transactions), atteignait la limite sans conclure, et l'Advisor renvoyait une
escalade générique à confiance 0 : chaque carte refusée partait chez le
superviseur. Chaque résultat d'outil renvoyé au modèle est aussi borné à
`MAX_TOOL_RESULT_CHARS` (1 500 caractères) ; les sources transmises au backend ne
le sont pas. Mesuré sur ce cas : environ 6 960 jetons par message au lieu de
8 560.

## Modèles et disponibilité

`LLM_LIVE_ADVISOR_MODEL` choisit le modèle des réponses clients automatiques.
`LLM_SIMULATION_ADVISOR_MODEL` choisit celui des dialogues simulés. Une valeur
vide conserve `LLM_MODEL_ADVANCED`. La configuration locale sélectionne
`openai/gpt-oss-20b` pour ces deux parcours, après constat d’un quota bloquant
sur `openai/gpt-oss-120b`. Le modèle avancé reste configuré et disponible pour
un choix explicite ultérieur.

Une absence d’appel structuré reçoit une tentative de conclusion avec
`finalize_response` imposé, sans répéter les outils déjà exécutés. La validation
de la décision et les contrôles sur les opérations bancaires restent appliqués.
Une salutation ou un repère de navigation fourni par l’application ne nécessite
pas de consulter la fiche client.

Les quotas et pannes du fournisseur restent des dépendances externes : aucun
modèle n’offre une disponibilité absolue. Le service expose un état d’erreur
contrôlé au lieu d’inventer une réponse réussie.

## Demande humaine, suggestions et explications

Une demande explicite telle que « Je veux un conseiller humain » déclenche une
escalade déterministe, sans appel au modèle. L’IA continue avant la première
réponse humaine ; l’escalade et l’affectation ne clôturent pas la discussion.

Le mode AI `PREVIEW`, utilisé par la route Next authentifiée `/api/ai-preview`
après vérification des droits sur la conversation, permet uniquement les outils
`get_customer`, `get_transactions`, `check_system_status` et
`search_knowledge_base`. Les opérations sont refusées avant leur exécution.
`SIMULATION` garde l’interdiction des outils backend réels. Un outil interdit
conclut le tour avec une limite explicite plutôt que d’être rappelé en boucle.

Chaque réponse automatique effectivement publiée enregistre une décision
`CUSTOMER_ADVISOR` dans la transaction du message : conversation, message,
message déclencheur, intention, confiance déclarée, action demandée, motif et
latence. Les sources et outils restent attachés au message. Un résultat périmé
ou empêché par une intervention humaine ne crée pas d’explication de réponse envoyée.

`GET /api/v1/conversations/{id}/messages/{messageId}/explanation` est réservé
au conseiller affecté, au superviseur et à l’administrateur. Le client reçoit
403 ; un conseiller étranger à la conversation reçoit 404. Les anciens messages
sans décision n’affichent aucune confiance inventée. Le panneau expose les
références et le succès des vérifications, sans arguments d’outils, résultats
bancaires bruts ni raisonnement interne du modèle.
