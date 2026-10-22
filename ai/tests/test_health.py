from fastapi.testclient import TestClient

from app import __version__
from app.api.health import CAPABILITIES
from app.main import create_app

client = TestClient(create_app())

# Paths that make up each advertised capability.
CAPABILITY_ROUTES = {"classify": "/v1/classify"}


def test_health_reports_ok_and_version():
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "finguard-ai"
    assert body["version"] == __version__


def test_capabilities_equal_the_implemented_endpoints():
    capabilities = client.get("/health").json()["capabilities"]
    assert capabilities == CAPABILITIES == ["classify"]

    paths = create_app().openapi()["paths"]
    versioned_routes = {path for path in paths if path.startswith("/v1/")}
    assert versioned_routes == {CAPABILITY_ROUTES[c] for c in capabilities}


def test_health_does_not_claim_an_ml_model_or_calibrated_confidence():
    classification = client.get("/health").json()["classification"]
    assert classification["method"] == "rules"
    assert classification["ml_model"] is False
    assert classification["confidence_calibrated"] is False
    assert classification["version"].startswith("rules-")


def test_unimplemented_capabilities_are_not_routed():
    assert client.post("/v1/forecast", json={}).status_code == 404


def test_unknown_route_is_404():
    assert client.get("/nope").status_code == 404
