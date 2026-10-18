# Flux du jumeau numérique : runs par scénario et stratégie

## Vue d'ensemble

Un run du jumeau rejoue une journée simulée du centre de contact (les quatre files
ACCOUNTS, CARDS, CREDIT, FRAUD) sous une stratégie de pilotage, et garde ses KPI pour
comparer les stratégies.

```text
Superviseur
  └─ POST /api/v1/twin-runs { scenarioId, strategyCode, seed }
       └─ backend : scénario → charge, durée, effectif ; simulation_run RUNNING
            └─ POST {AI_SERVICE_BASE_URL}/ai/workforce/twin-run  (X-AI-Key)
                 └─ ai-service : un épisode de CallCenterEnv, graine fixée
            └─ run_kpi + simulation_run COMPLETED  (ou FAILED + 503)
  └─ GET /api/v1/twin-runs?scenarioId=…   comparaison des runs terminés
  └─ GET /api/v1/twin-runs/{runId}
```

## Correspondances

| Backend | ai-service |
|---|---|
| `control_strategy` `STATIC_FIFO`, `THRESHOLD`, `RL_PPO_V1` | `STATIC_FIFO`, `THRESHOLD`, `PPO` |
| `scenario.load_profile` `LOW`, `MEDIUM`, `SATURATED` | échelle d'arrivée 0,2 ; 0,35 ; 1,0 (celles de l'évaluation PPO) |
| `scenario.duration_minutes` | `duration_minutes` (1 à 1 440) |
| `scenario.advisor_count` | `advisors_per_skill` = max(1, advisor_count / 4), 50 au plus |

Un scénario hors de ces limites est refusé en 400 avant tout run. La même graine rejoue
les mêmes clients pour chaque stratégie : la comparaison est équitable et reproductible
(même scénario, même stratégie, même graine : mêmes KPI). Un épisode de 8 heures se
simule en moins d'une seconde, d'où un appel synchrone.

## KPI conservés (`run_kpi`)

| Colonne | Valeur |
|---|---|
| `total_conversations` | clients arrivés dans la journée |
| `sla_ratio` | SLA sur **toutes les arrivées** (abandons compris), la référence demandée par l'audit |
| `abandon_ratio` | abandons sur les arrivées |
| `avg_wait_seconds`, `p95_wait_seconds` | attente moyenne et 95e centile |

Le jumeau renvoie aussi les clients servis, le SLA sur les seuls clients servis et les
réaffectations ; le schéma n'a pas de colonne pour eux (aucune migration), ils ne sont
donc pas conservés et toutes les lectures affichent les mêmes chiffres conservés.

## Échecs et unicité

Un même triplet (scénario, stratégie, graine) ne se termine qu'une fois (`uq_run`, 409).
Si l'ai-service est indisponible, le run est conservé `FAILED` et l'appel répond 503
`WORKFORCE_UNAVAILABLE` ; la même requête peut être renvoyée : le run FAILED est relancé
sur place (mise à jour conditionnelle, une seule relance gagne en cas de concurrence).
Un run n'est jamais laissé `RUNNING`.

Les runs du jumeau partagent `simulation_run` avec les simulations conversationnelles :
un run sans conversation est un run du jumeau. `GET /api/v1/twin-runs/{id}` répond 404
pour une simulation conversationnelle, et `GET /api/v1/simulation-runs/{id}` 404 pour un
run du jumeau. Comme `uq_run` est commun, un triplet déjà pris par une simulation
conversationnelle est refusé en 409 pour le jumeau.

## Résultats mesurés (image réelle, 12 conseillers, journée de 8 h, moyenne des graines 7 et 8)

| Charge | Stratégie | SLA (toutes arrivées) | Abandons | Attente moyenne |
|---|---|---|---|---|
| Faible | STATIC_FIFO | 0,665 | 1,0 % | 91 s |
| Faible | THRESHOLD | 0,680 | 0,1 % | 71 s |
| Faible | RL_PPO_V1 | **0,698** | 5,9 % | 103 s |
| Moyenne | STATIC_FIFO | 0,313 | 12,9 % | 230 s |
| Moyenne | THRESHOLD | 0,270 | 9,1 % | 210 s |
| Moyenne | RL_PPO_V1 | **0,426** | 18,9 % | 201 s |

PPO obtient le meilleur SLA, au prix de davantage d'abandons en charge faible et
moyenne : c'est la limite déjà documentée dans `WORKFORCE_FLOW.md`, désormais mesurable
depuis la plateforme. En charge saturée, 12 conseillers sont submergés (environ 57 %
d'abandons quelle que soit la stratégie).
