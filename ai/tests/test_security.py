"""Service credential policy (S0-06 / KI-033): mandatory outside dev, rotation window, no 500 on odd headers."""

import pytest
from fastapi.testclient import TestClient

from app.core.config import MIN_TOKEN_LENGTH, Settings, get_settings, validate_settings
from app.main import create_app

TOKEN = "t" * MIN_TOKEN_LENGTH
OLD_TOKEN = "o" * MIN_TOKEN_LENGTH
BODY = {"transactions": [{"id": "1", "description": "ZOMATO"}]}


def client(**settings) -> TestClient:
    return TestClient(create_app(Settings(**settings)))


def post(c: TestClient, token: str | None):
    headers = {} if token is None else {"X-Internal-Token": token}
    return c.post("/v1/classify", json=BODY, headers=headers)


# --- startup policy -------------------------------------------------------------------------


def test_dev_may_run_without_a_token():
    validate_settings(Settings(env="dev"))
    assert post(client(env="dev"), None).status_code == 200


@pytest.mark.parametrize("env", ["staging", "prod", "production"])
def test_protected_environments_refuse_to_start_without_a_token(env):
    with pytest.raises(RuntimeError, match="AI_INTERNAL_TOKEN"):
        create_app(Settings(env=env))


@pytest.mark.parametrize("env", ["staging", "prod"])
def test_protected_environments_refuse_a_short_token(env):
    with pytest.raises(RuntimeError, match="at least"):
        create_app(Settings(env=env, internal_token="short"))


def test_a_short_previous_token_is_refused():
    with pytest.raises(RuntimeError, match="PREVIOUS"):
        create_app(Settings(env="prod", internal_token=TOKEN, internal_token_previous="short"))


def test_an_unknown_environment_name_fails_closed():
    with pytest.raises(RuntimeError, match="not recognised"):
        create_app(Settings(env="qa"))


def test_a_protected_environment_starts_with_a_strong_token():
    assert post(client(env="prod", internal_token=TOKEN), TOKEN).status_code == 200


def test_environment_variables_are_read(monkeypatch):
    monkeypatch.setenv("AI_ENV", " Staging ")
    monkeypatch.setenv("AI_INTERNAL_TOKEN", TOKEN)
    monkeypatch.setenv("AI_INTERNAL_TOKEN_PREVIOUS", OLD_TOKEN)
    settings = get_settings()
    assert (settings.env, settings.internal_token, settings.internal_token_previous) == (
        "staging", TOKEN, OLD_TOKEN)


def test_environment_defaults_to_dev(monkeypatch):
    for name in ("AI_ENV", "AI_INTERNAL_TOKEN", "AI_INTERNAL_TOKEN_PREVIOUS"):
        monkeypatch.delenv(name, raising=False)
    assert get_settings().env == "dev"


# --- request authentication -------------------------------------------------------------------


def test_missing_or_wrong_token_is_rejected():
    secured = client(env="prod", internal_token=TOKEN)
    assert post(secured, None).status_code == 401
    assert post(secured, "w" * MIN_TOKEN_LENGTH).status_code == 401
    assert post(secured, "").status_code == 401


def test_health_stays_open_without_a_token():
    assert client(env="prod", internal_token=TOKEN).get("/health").status_code == 200


def test_rotation_window_accepts_both_the_current_and_the_previous_token():
    rotating = client(env="prod", internal_token=TOKEN, internal_token_previous=OLD_TOKEN)
    assert post(rotating, TOKEN).status_code == 200
    assert post(rotating, OLD_TOKEN).status_code == 200
    assert post(rotating, "w" * MIN_TOKEN_LENGTH).status_code == 401


def test_after_rotation_the_previous_token_stops_working():
    rotated = client(env="prod", internal_token=TOKEN)
    assert post(rotated, OLD_TOKEN).status_code == 401


def test_a_non_ascii_token_header_is_a_401_not_a_server_error():
    secured = client(env="prod", internal_token=TOKEN)
    response = secured.post(
        "/v1/classify", json=BODY, headers={"X-Internal-Token": "tökén".encode("latin-1")})
    assert response.status_code == 401
