# Corrections Workforce et utilisation de l'API

Le modèle conserve ACCOUNT, CARD, CREDIT, ADVISORY et les actions 0..4.
0 = conserver ; 1..4 = renforcer la file correspondante avec un conseiller.

## Chronologie corrigée

À t : libération des appels terminés, admission des arrivées <= t, décision,
puis affectation FIFO. L'horloge avance à t+1 ; libération, admission et calcul
de patience préparent le prochain état. Un client arrivé à t+0,75 est donc
servi au plus tôt à t+1. La résolution reste discrète à la minute :
elle ajoute une attente de discrétisation, mais aucune attente négative.

La patience dépend du temps réellement passé depuis l'arrivée.
resolved conserve sa signification historique de prise en charge.
Il ne représente pas une résolution bancaire validée.

## Même action dans l'API et le simulateur

actions.select_donor est utilisée par PPO, le simulateur et THRESHOLD.
Le donneur est le premier pool libre, hors cible, dans l'ordre :
ACCOUNT, CARD, CREDIT, ADVISORY. Dans ce pool, le simulateur prend le premier
conseiller libre. Le backend peut choisir un conseiller libre éligible du même
pool. Après son appel, il doit revenir à sa compétence d'origine,
comme dans _free_finished_advisors. L'API ne fait que proposer l'action.

Avant d'appeler POST /ai/workforce/decide :
- queues : nombres en attente, pour les quatre compétences.
- avg_wait : secondes (converties en minutes par l'adaptateur).
- available : conseillers libres seulement.
- sla_today : ratio 0..1 ; hour : heure entière ; trend : 1 pour reproduire
  le simulateur actuel, où cet indicateur est constant.

Le corps JSON et les routes existantes sont conservés.
capabilities expose donor_rule et advisor_return_policy.

## Récompense et apprentissage

La récompense utilise les prises en charge et abandons du pas courant,
avec un bonus SLA local et une pénalité de file. Les composantes sont ramenées
à une échelle fixe de capacité. Une minute sans client ne donne plus
de bonus SLA artificiel. Les poids sont un point de départ expérimental,
pas des pondérations démontrées optimales.

L'entraînement alterne des charges 0,2, 0,35 et 1 fois le profil d'origine.
La configuration par défaut du simulateur reste à charge 1.
La normalisation fixe des 15 observations est intégrée à WorkforceFeatures
dans le modèle : aucun traitement supplémentaire n'est nécessaire dans l'API.
L'ancien modèle peut toujours être chargé ; les nouveaux modèles doivent être
livrés avec le code app/workforce/features.py.

Les durées de service sont tirées par client à reset : avec la même seed,
toutes les stratégies reçoivent les mêmes clients ET les mêmes durées.
Les générateurs aléatoires sont locaux.

## Réentraîner et comparer

Depuis la racine :

```powershell
.\\.venv\\Scripts\\python.exe -B -m app.workforce.train --timesteps 100000 --save-path artifacts/workforce_v2
.\\.venv\\Scripts\\python.exe -B -m app.workforce.evaluate --models workforce_policy.zip artifacts/workforce_v2.zip artifacts/best/best_model.zip --output artifacts/workforce_evaluation.json
```

best_model.zip est sélectionné sur la récompense de validation en charge
moyenne, sur d'autres seeds que le rapport final. workforce_v2.zip est le
dernier état de l'entraînement. Répéter aussi l'entraînement avec plusieurs
seeds pour une conclusion scientifique solide.

Le rapport contient moyenne et écart-type sur cinq seeds, trois charges,
STATIC_FIFO, THRESHOLD (seuil 3) et les modèles demandés.
sla_rate est calculé sur les clients pris en charge.
sla_all_arrivals inclut les abandons et clients encore en attente au dénominateur.
avg_wait_seconds et p95_wait_seconds concernent les clients pris en charge.
pending mesure les clients encore en attente à la fermeture.

## Choisir le modèle servi

Configurer WORKFORCE_MODEL_PATH dans .env puis redémarrer FastAPI, car
le modèle est chargé en cache :

```dotenv
WORKFORCE_MODEL_PATH=artifacts/workforce_improved.zip
```

Ne pas comparer les anciens KPI produits par l'ancien simulateur aux nouveaux :
le biais de chronologie a été corrigé. Le rapport réévalue tous les modèles
dans le même environnement corrigé. En saturation, la réaffectation ne peut
pas compenser un effectif global insuffisant.

## Resultats de la premiere execution

Le modele active par defaut est artifacts/workforce_improved.zip : copie du
checkpoint retenu sur la validation, a 30 000 pas (seed entrainement 7).
Le modele historique workforce_policy.zip reste disponible.
Le rapport artifacts/workforce_evaluation.json contient les 75 episodes
agre ges en moyenne et ecart-type.

Charge moyenne, ancien -> nouveau : abandon 46,8 % -> 15,4 %,
attente moyenne des servis 268,4 s -> 201,3 s,
clients dans le SLA / toutes les arrivees 30,3 % -> 37,9 %.
Le SLA parmi les seuls clients servis diminue de 59,2 % a 45,5 % :
le nouveau modele sert davantage de clients, y compris des clients ayant
attendu plus longtemps. Comparer les deux definitions du SLA.

Le modele ne domine pas les baselines : a faible charge THRESHOLD est meilleur,
et en charge moyenne STATIC_FIFO abandonne moins (12,7 % contre 15,4 %),
mais sert moins de clients dans le SLA rapporte a toutes les arrivees
(30,9 % contre 37,9 %). Ce bilan repose sur une seule seed entrainement.
Si .env definit WORKFORCE_MODEL_PATH, cette valeur prime sur le defaut.
