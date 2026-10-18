"""Authentication middleware for AI service routes."""

from hmac import compare_digest

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


_MISSING_KEY = (
    "AI_SERVICE_KEY is required: /ai/* would otherwise be open to anyone who can reach the service. "
    "Set the same value as the backend's AI_SERVICE_KEY, or AI_SERVICE_AUTH_DISABLED=true for local "
    "development only."
)


def require_ai_service_key(ai_service_key: str, auth_disabled: bool) -> None:
    """Fails at startup, not at the first request, when /ai/* would run unauthenticated."""
    if not ai_service_key.strip() and not auth_disabled:
        raise RuntimeError(_MISSING_KEY)


class AIServiceKeyMiddleware:
    """Requires X-AI-Key on /ai/*. Fails closed: an empty key is refused unless explicitly allowed."""

    def __init__(self, app: ASGIApp, ai_service_key: str, allow_unauthenticated: bool = False) -> None:
        if not ai_service_key.strip() and not allow_unauthenticated:
            raise ValueError(_MISSING_KEY)
        self.app = app
        self.ai_service_key = ai_service_key

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not self.ai_service_key:
            await self.app(scope, receive, send)
            return

        path = scope["path"]
        if path != "/ai" and not path.startswith("/ai/"):
            await self.app(scope, receive, send)
            return

        provided_key = Headers(scope=scope).get("X-AI-Key")
        if provided_key is None or not compare_digest(
            provided_key.encode("utf-8"), self.ai_service_key.encode("utf-8")
        ):
            response = JSONResponse(
                status_code=401,
                content={"detail": "Missing or invalid X-AI-Key"},
            )
            await response(scope, receive, send)
            return

        await self.app(scope, receive, send)
