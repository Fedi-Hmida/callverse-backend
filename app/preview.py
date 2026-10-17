"""Isolated local preview. Existing main app and Spring API remain unchanged."""
import os
import secrets
from contextvars import ContextVar
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from app.advisor.router import router as advisor
from app.quality.router import router as quality
from app.workforce.router import router as workforce
from app.simulator.router import router as simulator
from app.clients.backend_client import backend_client
from app.advisor import nodes

scope = ContextVar("preview_scope", default=None)

async def read(path, params=None):
    import httpx
    current = scope.get()
    if not current:
        raise PermissionError("Missing authenticated preview context")
    async with httpx.AsyncClient(base_url=backend_client._base_url, timeout=10) as client:
        response = await client.get('/api/v1' + path, params=params,
                                    headers={'Authorization': current['authorization']})
        response.raise_for_status()
        return response.json()

async def customer(customer_id):
    if str(customer_id) != scope.get()['customer_id']:
        raise PermissionError("Customer outside current conversation")
    return await read('/customers/' + str(customer_id))

async def transactions(customer_id, n=3):
    await customer(customer_id)
    return await read('/customers/' + str(customer_id) + '/transactions', {'count': min(max(n, 1), 10)})

async def incidents(zone):
    # The model's service name is not a customer region; return the published incident list.
    return await read('/service-incidents')

async def search(query, top_k=3):
    result = await read('/kb/articles', {'q': query, 'limit': min(max(top_k, 1), 5)})
    articles = result.get('articles', [])
    return [{'article_id': a['id'], 'title': a['title'], 'excerpt': a.get('content', ''),
             'score': 0.0} for a in articles]  # Text search, not a vector similarity score.

async def blocked(**kwargs):
    raise PermissionError("Preview: business writes require the existing human workflow")

nodes.TOOL_IMPLEMENTATIONS.update(get_customer=customer, get_transactions=transactions,
    check_system_status=incidents, create_case=blocked, apply_credit=blocked, escalate=blocked)
nodes.search_knowledge_base = search

app = FastAPI(title="CallVerse isolated AI preview")

@app.middleware('http')
async def authenticate(request: Request, call_next):
    import asyncio
    key = os.environ.get('AI_PREVIEW_KEY', '')
    if not key or not secrets.compare_digest(request.headers.get('x-preview-key', ''), key):
        return JSONResponse({'detail': 'Unauthorized'}, status_code=401)
    token = scope.set({'authorization': request.headers.get('x-backend-authorization', ''),
                       'customer_id': request.headers.get('x-customer-id', '')})
    try:
        async with asyncio.timeout(150):
            return await call_next(request)
    except TimeoutError:
        return JSONResponse({'detail': 'Local model timed out'}, status_code=504)
    finally:
        scope.reset(token)

app.include_router(advisor, prefix='/ai/advisor')
app.include_router(quality, prefix='/ai/quality')
app.include_router(workforce, prefix='/ai/workforce')
app.include_router(simulator, prefix='/ai/simulator')

@app.get('/health')
def health():
    return {'status': 'ok', 'mode': 'read-only-preview', 'knowledge_search': 'backend-text'}
