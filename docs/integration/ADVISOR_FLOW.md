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
                 └─ exécuteur borné, une tâche en cours par conversation
                      └─ POST {AI_SERVICE_BASE_URL}/ai/advisor/respond
                           └─ ai-service appelle l'Advisor et ses outils /internal
                                └─ backend persiste le tour Advisor
                                     └─ événement temps réel du message
```

Seuls les messages dont l'émetteur est `CUSTOMER` déclenchent ce flux. Les
conversations doivent être `ASSIGNED` ou `ACTIVE`. Les conversations `QUEUED`
et `ESCALATED` sont ignorées ; la machine d'états n'est pas modifiée.

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

Lorsque `AI_SERVICE_KEY` est défini, l'ai-service exige la même valeur sur les
routes `/ai/*`, sous l'en-tête `X-AI-Key`. `/health` reste accessible sans clé.
Lorsque la variable est vide ou absente, cette authentification est désactivée
pour conserver la compatibilité des environnements qui ne l'ont pas configurée.
Pour activer la protection, configurez la même clé dans l'environnement du
backend et de l'ai-service ; ne placez jamais sa valeur dans un fichier suivi
par Git. `INTERNAL_SERVICE_KEY` est une clé distincte utilisée dans l'autre
sens, pour protéger les appels outils de l'ai-service vers `/internal/**`.

## Persistance, diffusion et comportement dégradé

Le backend enregistre la réponse comme un message `ADVISOR` avec
`ai_generated=true`. Les `sources` et `tool_calls` sont stockés dans les
colonnes existantes de la table `message`. Ils ne sont pas ajoutés au
`MessageRecord`, aux routes publiques ou aux événements temps réel ; leur
exposition reste bloquée en attendant la confirmation de la règle A9.

Avant d'enregistrer la réponse, le backend reprend le verrou de la conversation
et vérifie que le client n'a pas envoyé un message plus récent et que l'état
permet toujours un tour Advisor. Une réponse devenue obsolète est abandonnée.
Un échec réseau, un délai dépassé, une réponse invalide ou une saturation de
l'exécuteur est journalisé sans secret ; la conversation reste disponible pour
un conseiller humain. Aucun échec n'entraîne d'escalade automatique.

Limites connues : les conversations `QUEUED` ne sont pas traitées, les traces
ne sont pas exposées (règle A9 non confirmée) et le contexte client est réduit
aux données que le backend connaît déjà.
