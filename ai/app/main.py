"""FastAPI application factory.

Run locally:  uvicorn app.main:app --reload --port 8000
"""

import logging

from fastapi import FastAPI

from app import __version__
from app.api import classify, health
from app.classification.engine import build_default_classifier
from app.core.config import Settings, get_settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(
        title="FinGuard AI service",
        version=__version__,
        description="Stateless computation service called by the Spring Boot backend.",
    )
    app.state.settings = settings
    app.state.classifier = build_default_classifier()
    app.include_router(health.router)
    app.include_router(classify.router)
    return app


app = create_app()
