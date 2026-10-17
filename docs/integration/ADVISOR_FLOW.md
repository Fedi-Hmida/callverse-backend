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

Seuls les messages dont l'émetteur est `CUSTOMER` déclenchent ce flux. Les
conversations doivent être `ASSIGNED` ou `ACTIVE`. Les conversations `QUEUED`
et `ESCALATED` sont ignorées ; la machine d'états n'est pas modifiée.

## Mode de l'Advisor et reprise par l'humain

`ai.advisor.mode` (variable `AI_ADVISOR_MODE`) vaut `OFF` par défaut : une
conversation en direct appartient à son conseiller humain et rien n'est planifié.
En `AUTONOMOUS`, l'IA répond aux messages du client **jusqu'à ce qu'un conseiller
humain écrive** dans la conversation (message `ADVISOR` avec `ai_generated=false`).
Dès lors l'humain a repris la main et l'IA se tait : le client ne reçoit jamais
deux réponses. Si l'humain écrit pendant que l'IA réfléchit, son message devient
le plus récent et la vérification sous verrou abandonne la réponse de l'IA.

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
Un échec réseau, un délai dépassé, une réponse invalide ou une saturation de
l'exécuteur est journalisé sans secret ; la conversation reste disponible pour
un conseiller humain. Aucun échec n'entraîne d'escalade automatique.

## Escalade demandée par l'IA

Quand l'Advisor renvoie `action.type = ESCALATE` (confiance basse ou demande
explicite), le backend publie d'abord la réponse (« je transmets à un
superviseur »), ce qui fait passer la conversation de `ASSIGNED` à `ACTIVE`,
puis lève une **vraie escalade** par le cas d'usage existant, avec
`raised_by = AI` et la raison fournie (« Escalade demandée par l'assistant IA »
si absente, 255 caractères au plus). Le superviseur reçoit l'alerte et voit la
conversation `ESCALATED` dans sa liste. Un refus (conversation déjà résolue ou
déjà escaladée) est journalisé et laisse la réponse publiée.

C'est le **seul chemin d'escalade** : l'outil `escalate` a été retiré de l'Advisor.
Escalader par l'outil `/internal` en cours de tour faisait passer la conversation
en `ESCALATED` avant la réponse, qui était alors abandonnée (le client ne
recevait rien), ou échouait en 409 au premier tour (`ASSIGNED`).

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

Limites connues : les conversations `QUEUED` ne sont pas traitées, les traces
ne sont pas exposées (règle A9 non confirmée), le contexte client est réduit
aux données que le backend connaît déjà, le **nom du client** atteint encore
le modèle par l'outil `get_customer`, et les libellés de transactions renvoyés
par `/internal` n'ont que leurs IBAN masqués (cartes, e-mails et téléphones
non) : les masquer sur cette route les masquerait aussi pour le personnel, une
décision à prendre en équipe.
