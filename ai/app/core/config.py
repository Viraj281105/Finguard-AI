"""Runtime settings, read from environment variables (see ../../.env.example)."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    log_level: str = "INFO"
    internal_token: str = ""
    """Shared secret the backend sends as ``X-Internal-Token``. Empty disables the check (local dev)."""


def get_settings() -> Settings:
    return Settings(
        log_level=os.environ.get("AI_LOG_LEVEL", "INFO").upper(),
        internal_token=os.environ.get("AI_INTERNAL_TOKEN", ""),
    )
