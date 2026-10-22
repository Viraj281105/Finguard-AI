"""Service-to-service authentication: a shared secret in the ``X-Internal-Token`` header."""

import hmac

from fastapi import Header, HTTPException, Request, status


def require_internal_token(
    request: Request, x_internal_token: str | None = Header(default=None)
) -> None:
    expected: str = request.app.state.settings.internal_token
    if not expected:
        return  # no secret configured (local development)
    if x_internal_token is None or not hmac.compare_digest(x_internal_token, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid internal token")
