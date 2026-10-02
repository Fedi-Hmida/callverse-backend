"""
Point d'entrée FastAPI — monte les 4 routers des agents.
"""
from contextlib import asynccontextmanager
from fastapi import FastAPI

from app.advisor.router import router as advisor_router
from app.simulator.router import router as simulator_router
from app.workforce.router import router as workforce_router
from app.quality.router import router as quality_router
from app.clients.backend_client import backend_client
from app.db.pgvector import close_pool


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await backend_client.aclose()
    await close_pool()


app = FastAPI(title="CallVerse AI Service", lifespan=lifespan)

app.include_router(advisor_router, prefix="/ai/advisor", tags=["advisor"])
app.include_router(simulator_router, prefix="/ai/simulator", tags=["simulator"])
app.include_router(workforce_router, prefix="/ai/workforce", tags=["workforce"])
app.include_router(quality_router, prefix="/ai/quality", tags=["quality"])


@app.get("/health")
def health():
    return {"status": "ok"}
