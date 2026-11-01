"""Service-to-service authentication: a shared secret in the ``X-Internal-Token`` header."""

import hmac

from fastapi import Header, HTTPException, Request, status


def require_internal_token(
    request: Request, x_internal_token: str | None = Header(default=None)
) -> None:
    settings = request.app.state.settings
    accepted = [t for t in (settings.internal_token, settings.internal_token_previous) if t]
    if not accepted:
        return  # no secret configured; only possible with AI_ENV=dev (see validate_settings)
    if x_internal_token is not None:
        supplied = x_internal_token.encode("utf-8")
        # Compare against every accepted secret (no early exit) so timing does not reveal which matched.
        matches = [hmac.compare_digest(supplied, t.encode("utf-8")) for t in accepted]
        if any(matches):
            return
    raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid internal token")
