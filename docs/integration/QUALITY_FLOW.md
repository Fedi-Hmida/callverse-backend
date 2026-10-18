# Quality Analyst — flux backend ↔ ai-service

## Déclenchement et données

Quand Spring publie le changement de statut `RESOLVED`, son adaptateur de publication
programme l'évaluation après commit sur un exécuteur borné. Une seule évaluation
est en cours par conversation dans un processus. Les événements d'abandon et les
conversations non résolues ne déclenchent pas l'agent.

Le cas d'usage charge au plus 500 messages récents, dans l'ordre chronologique,
et n'envoie que les rôles `CUSTOMER` et `ADVISOR`. Il ignore l'évaluation si le
transcript ne contient pas au moins deux messages et un message `ADVISOR`.
Le corps envoyé à `POST /ai/quality/evaluate` contient `conversation_id`,
`messages` (IBAN, numéros de carte, e-mails et téléphones masqués) et
`resolved=true`, ainsi que le contexte de notation :

- `source_documents` : les articles de la base de connaissances **cités par
  l'IA** dans ses tours de la conversation (trace privée `sources`), un document
  par article (`doc_id` = identifiant de l'article, `chunk_id` = `article`,
  contenu = titre et texte, 1 500 caractères au plus), les 10 premiers articles
  encore existants dans l'ordre des citations ;
- `applicable_rules` : les cinq règles propres à la banque du livret conceptuel
  (§ 3.3), détenues par le backend (`BankingProcedureRules`).

`advisor_response` est omis. L'appel utilise `X-AI-Key`, 2 secondes à la
connexion et **130 secondes en lecture**, au-delà du délai du juge (120 s par
défaut) : l'appel tourne en arrière-plan, attendre ne coûte rien, abandonner
avant ferait perdre une note en cours.

Le juge rejette (502) une sortie incomplète au lieu de l'inventer : un score
absent ou illisible, une preuve absente ou un index de message invalide ne sont
plus remplacés par 5 ou par une preuve par défaut. Une affirmation mal située
(index invalide ou message du client) est écartée. **Un rejet est définitif** :
l'évaluation n'est pas retentée et la conversation reste sans note IA (reprise
à prévoir en P2). L'article envoyé est sa version actuelle, même s'il a été
modifié depuis que l'IA l'a cité. Les traces ne sont pas exposées par l'API
(règle A9 non confirmée).

## Persistance et comportement dégradé

Une réponse valide est enregistrée dans `quality_evaluation` avec
`evaluator=AI`, le score global, les six notes, l'explication et les indicateurs
de défaut. Les noms de critères reçus en minuscules sont normalisés en
majuscules pour correspondre aux codes déjà semés dans `quality_criterion`.
Aucune migration n'est requise. Le schéma ne stocke pas les preuves détaillées,
les recommandations ni les évaluations de claims ; ces champs du contrat AI ne
sont pas persistés.

Une indisponibilité (dont 5xx et timeout), une réponse invalide ou le rejet de
l'exécuteur est journalisé en avertissement sans inclure de secret ni de corps
de réponse. L'échec d'évaluation ne change pas la résolution et ne bloque pas
la réponse HTTP au conseiller. Une évaluation AI déjà persistée n'est pas
recalculée automatiquement.

Il n'existe pas encore de route de lecture pour afficher ces évaluations dans
l'interface. Le Client Simulator reste différé et n'est pas utilisé sur les
conversations client réelles.

## Vérifications

- Tests ciblés backend : transport HTTP, cas d'usage, déclenchement après commit
  et mapping vers le schéma — 9/9 réussis.
- `LayerDependencyTest` — 7/7 réussis.
- Suite complète Spring — 446 tests, 1 échec connu dans
  `ConstraintEnforcementTest.queueQueryUsesItsIndex`; ce test échoue dans la
  suite complète et passe lorsqu'il est lancé seul. `StompConversationTest`
  passe 5/5.
