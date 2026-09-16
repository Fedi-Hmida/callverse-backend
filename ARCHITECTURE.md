# Architecture technique — Service IA CallVerse

Ce document décrit l'architecture au niveau code du service IA (FastAPI), à committer dans le repo (`ARCHITECTURE.md` à la racine) pour que Douaa et Ines travaillent sur le même schéma dès le premier commit.

## 1. Principe directeur

Un seul service FastAPI héberge les 4 agents. Chaque agent est un module Python indépendant (son propre dossier), mais tous partagent une couche `contracts/` : les schémas Pydantic qui définissent ce qu'un agent produit et ce qu'un autre consomme. C'est cette couche, pas le code des agents, qui doit être figée en premier et en accord entre vous deux.

## 2. Arborescence du repo

```
ai-service/
├── app/
│   ├── main.py                     # instancie FastAPI, monte les 4 routers
│   ├── config.py                   # Settings (pydantic-settings) : URL backend Spring Boot, clé LLM, DSN pgvector
│   │
│   ├── contracts/                  # === couche de contrat partagée — à figer en premier ===
│   │   ├── advisor.py              # AdvisorResponse, SourceRef, ToolCall
│   │   ├── simulator.py            # SimulatorMessage, SimulatorState
│   │   ├── workforce.py            # WorkforceState, WorkforceAction
│   │   └── quality.py              # QualityScore, CriterionScore
│   │
│   ├── advisor/                    # Douaa — Customer Advisor
│   │   ├── router.py               # POST /ai/advisor/respond
│   │   ├── state.py                # AdvisorState (TypedDict, état du graphe)
│   │   ├── graph.py                # construction du StateGraph LangGraph
│   │   ├── nodes.py                # fonctions des nœuds : decide, retrieve, call_tool, evaluate, respond, escalate
│   │   ├── tools.py                # 7 outils mockés
│   │   ├── prompts.py              # templates de prompts système
│   │   └── kb/
│   │       ├── ingest.py           # chargement des 20-40 articles → chunks → embeddings bge-like
│   │       └── retriever.py        # requête pgvector (table kb_chunk, index HNSW)
│   │
│   ├── simulator/                  # Douaa — Client Simulator
│   │   ├── router.py               # POST /ai/simulator/next-message
│   │   ├── state.py                # SimulatorState (patience, satisfaction, objectif)
│   │   ├── graph.py                # StateGraph LangGraph
│   │   ├── profiles.py             # les 6 profils (prompt système + paramètres initiaux)
│   │   └── prompts.py
│   │
│   ├── workforce/                  # Ines — Workforce Manager
│   │   ├── router.py               # POST /ai/workforce/decide
│   │   ├── env.py                  # gymnasium.Env — CallCenterEnv (observation, action, reward, step, reset)
│   │   ├── arrivals.py             # générateur d'arrivées Poisson
│   │   ├── baselines.py            # STATIC_FIFO, THRESHOLD
│   │   ├── train.py                # script d'entraînement PPO (Stable-Baselines3)
│   │   └── policy.py               # charge le modèle entraîné, expose decide(state) -> WorkforceAction
│   │
│   ├── quality/                    # Ines — Quality Analyst
│   │   ├── router.py               # POST /ai/quality/evaluate
│   │   ├── rubric.py               # 6 critères pondérés
│   │   ├── judge.py                # appel LLM-as-judge, sortie JSON contrainte (schéma quality.py)
│   │   ├── sourcing_check.py       # détection de non-sourçage — consomme AdvisorResponse.sources/tool_calls
│   │   └── annotations/            # jeu de 30-50 conversations annotées à la main + calcul de Cohen's κ
│   │
│   ├── clients/
│   │   └── backend_client.py       # client HTTP vers le backend Spring Boot (outils métier)
│   │
│   └── db/
│       ├── models.py               # modèles SQLAlchemy (kb_chunk, conversation_log, ...)
│       └── pgvector.py             # connexion + requêtes vectorielles
│
├── tests/
│   ├── advisor/  ├── simulator/  ├── workforce/  └── quality/
├── notebooks/                      # analyse entraînement RL, validation κ
├── pyproject.toml
└── ARCHITECTURE.md                 # ce fichier
```

## 3. La couche `contracts/` — le seul code que vous écrivez à deux

```python
# app/contracts/advisor.py
from pydantic import BaseModel

class SourceRef(BaseModel):
    doc_id: str
    chunk_id: str
    score: float

class ToolCall(BaseModel):
    tool_name: str
    arguments: dict
    result: dict

class AdvisorResponse(BaseModel):
    answer: str
    sources: list[SourceRef]
    tool_calls: list[ToolCall]
    confidence: float          # 0-1, produit par le nœud "evaluate"
```

`quality/sourcing_check.py` importe directement `AdvisorResponse` — il ne réimplémente jamais sa propre version du schéma. C'est ce qui évite la dérive que je mentionnais : un seul endroit où le format peut changer, et un changement dans `contracts/advisor.py` casse visiblement les tests de `quality/` si l'un des deux oublie l'autre.

Même logique pour `workforce.py` et `simulator.py` : le `WorkforceState` que Ines consomme en entrée de son `env.py` doit correspondre à ce que produit potentiellement le simulateur (nombre de clients en attente, etc.) — à définir ensemble avant de coder l'environnement Gymnasium.

## 4. Squelette du graphe LangGraph (Customer Advisor)

```python
# app/advisor/state.py
from typing import TypedDict
from langchain_core.messages import BaseMessage

class AdvisorState(TypedDict):
    messages: list[BaseMessage]
    customer_id: str
    retrieved_docs: list[dict]
    tool_calls: list[dict]
    confidence: float
    next_action: str          # "rag" | "tool" | "respond" | "escalate"

# app/advisor/graph.py
from langgraph.graph import StateGraph, END
from .state import AdvisorState
from . import nodes

def build_graph() -> StateGraph:
    g = StateGraph(AdvisorState)
    g.add_node("decide_strategy", nodes.decide_strategy)
    g.add_node("retrieve_kb", nodes.retrieve_kb)
    g.add_node("call_tool", nodes.call_tool)
    g.add_node("evaluate_sufficiency", nodes.evaluate_sufficiency)
    g.add_node("respond", nodes.respond)
    g.add_node("escalate", nodes.escalate)

    g.set_entry_point("decide_strategy")
    g.add_conditional_edges(
        "decide_strategy",
        lambda s: s["next_action"],
        {"rag": "retrieve_kb", "tool": "call_tool"},
    )
    g.add_edge("retrieve_kb", "evaluate_sufficiency")
    g.add_edge("call_tool", "evaluate_sufficiency")
    g.add_conditional_edges(
        "evaluate_sufficiency",
        lambda s: "respond" if s["confidence"] >= 0.6 else "escalate",
        {"respond": "respond", "escalate": "escalate"},
    )
    g.add_edge("respond", END)
    g.add_edge("escalate", END)
    return g.compile()
```

Chaque fonction de `nodes.py` prend un `AdvisorState` et retourne un `AdvisorState` partiellement mis à jour — c'est le pattern LangGraph standard, aucune classe à inventer. Le routeur `advisor/router.py` invoque `graph.invoke(initial_state)` et sérialise le résultat final en `AdvisorResponse` (le contrat).

Le `Client Simulator` suit exactement le même squelette (state → graph → nodes), avec un état plus simple (`patience`, `satisfaction`, `objectif`) et un seul profil actif par session.

## 5. Squelette Workforce Manager (Ines)

```python
# app/workforce/env.py
import gymnasium as gym
from gymnasium import spaces

class CallCenterEnv(gym.Env):
    def __init__(self):
        self.observation_space = spaces.Box(...)   # ex: [queue_len, agents_libres, temps_attente_moyen]
        self.action_space = spaces.Discrete(...)    # ex: REASSIGN vers N pools

    def reset(self, seed=None, options=None): ...
    def step(self, action): ...                      # retourne obs, reward, terminated, truncated, info
```

`train.py` entraîne PPO (Stable-Baselines3) sur cet environnement après avoir validé les baselines `STATIC_FIFO` et `THRESHOLD`. `policy.py` charge le `.zip` entraîné et expose une fonction pure `decide(state: WorkforceState) -> WorkforceAction` que `router.py` appelle — le routeur ne connaît jamais Gymnasium ni Stable-Baselines3, seulement le contrat.

## 6. Squelette Quality Analyst (Ines)

```python
# app/quality/judge.py
from app.contracts.advisor import AdvisorResponse
from app.contracts.quality import QualityScore

def evaluate(conversation: list[dict], advisor_response: AdvisorResponse) -> QualityScore:
    # 1. sourcing_check.detect_unsourced_claims(advisor_response)
    # 2. appel LLM avec grille des 6 critères, sortie JSON contrainte (function calling ou response_format)
    # 3. parse -> QualityScore (validation Pydantic, pas de texte libre)
    ...
```

Le point sensible : `evaluate()` prend `AdvisorResponse` en paramètre typé, pas un `dict` générique — toute rupture de contrat casse à l'import, pas en production.

## 7. Correspondance avec la stratégie de branches Git

- `feature/advisor-*`, `feature/simulator-*` → dossiers `app/advisor/`, `app/simulator/` (Douaa)
- `feature/workforce-*`, `feature/quality-*` → dossiers `app/workforce/`, `app/quality/` (Ines)
- `feature/contracts` → dossier `app/contracts/`, mergé dans `develop` avant que quiconque code son agent

## 8. Premier commit recommandé

1. Squelette de dossiers vide + `pyproject.toml` + `main.py` qui monte 4 routers stub (retournent `501 Not Implemented`)
2. `app/contracts/*.py` complets et validés à deux (revue croisée obligatoire sur ce commit)
3. À partir de là, chacune travaille dans son dossier sans bloquer l'autre
