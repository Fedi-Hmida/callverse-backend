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
`messages` et `resolved=true`; `source_documents` et `applicable_rules` sont
vides. `advisor_response` est omis. L'appel utilise `X-AI-Key` et les délais
existants de 2 secondes à la connexion et 30 secondes en lecture.

Les colonnes privées `sources` et `tool_calls` du transcript ne sont ni chargées
ni transmises. Le Quality Analyst dispose donc d'un contexte réduit et ne peut
pas vérifier les déclarations contre les documents RAG. Cette intégration ne
confirme pas la règle A9 et n'expose pas les traces.

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
