"""Liveness and capability reporting, used by Docker health checks, developers and the backend."""

from fastapi import APIRouter, Request

from app import __version__

router = APIRouter()

# Computation endpoints the service actually serves. Keep in sync with the registered routers
# (tests/test_health.py checks every entry is a live route and every /v1 route is listed).
CAPABILITIES: list[str] = ["classify"]


@router.get("/health")
def health(request: Request) -> dict[str, object]:
    classifier = request.app.state.classifier
    return {
        "status": "ok",
        "service": "finguard-ai",
        "version": __version__,
        "capabilities": CAPABILITIES,
        "classification": {
            "method": "rules",
            "version": classifier.version,
            "ml_model": False,
            "confidence_calibrated": False,
        },
    }
