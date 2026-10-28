"""Runtime settings, read from environment variables (see ../../.env.example)."""

import os
from dataclasses import dataclass

DEV_ENV = "dev"
PROTECTED_ENVS = frozenset({"staging", "prod", "production"})
MIN_TOKEN_LENGTH = 32


@dataclass(frozen=True)
class Settings:
    log_level: str = "INFO"
    env: str = DEV_ENV
    """Deployment environment: ``dev`` (default), ``staging`` or ``prod``. Anything but ``dev`` requires a token."""
    internal_token: str = ""
    """Shared secret the backend sends as ``X-Internal-Token``. Empty disables the check (``dev`` only)."""
    internal_token_previous: str = ""
    """Previous secret, still accepted while the backend is switched to the new one (rotation window)."""


def get_settings() -> Settings:
    return Settings(
        log_level=os.environ.get("AI_LOG_LEVEL", "INFO").upper(),
        env=os.environ.get("AI_ENV", DEV_ENV).strip().lower(),
        internal_token=os.environ.get("AI_INTERNAL_TOKEN", ""),
        internal_token_previous=os.environ.get("AI_INTERNAL_TOKEN_PREVIOUS", ""),
    )


def validate_settings(settings: Settings) -> None:
    """Fail closed: outside ``dev`` the service refuses to start without a strong shared secret (KI-033)."""
    if settings.env != DEV_ENV and settings.env not in PROTECTED_ENVS:
        raise RuntimeError(
            f"AI_ENV={settings.env!r} is not recognised; use 'dev', 'staging' or 'prod'."
        )
    if settings.env == DEV_ENV:
        return
    if len(settings.internal_token) < MIN_TOKEN_LENGTH:
        raise RuntimeError(
            f"AI_INTERNAL_TOKEN must be set to at least {MIN_TOKEN_LENGTH} characters when "
            f"AI_ENV={settings.env}; refusing to start an unauthenticated service."
        )
    previous = settings.internal_token_previous
    if previous and len(previous) < MIN_TOKEN_LENGTH:
        raise RuntimeError(
            f"AI_INTERNAL_TOKEN_PREVIOUS must be empty or at least {MIN_TOKEN_LENGTH} characters."
        )
