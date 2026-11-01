import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app
from app.schemas.classify import MAX_BATCH, MAX_DESCRIPTION

client = TestClient(create_app(Settings()))
EXAMPLES = Path(__file__).resolve().parents[2] / "contracts" / "classify-examples.json"


def post(body):
    return client.post("/v1/classify", json=body)


def test_classifies_a_batch_and_echoes_every_id_in_order():
    response = post({"transactions": [
        {"id": "a", "description": "UPI-SWIGGY-swiggy@icici", "type": "EXPENSE", "amount": 450.5},
        {"id": "b", "description": "NEFT-ACME LTD-SALARY MARCH", "type": "INCOME", "amount": 75000},
        {"id": "c", "description": "XYZ TRADERS", "type": "EXPENSE"},
    ]})

    assert response.status_code == 200
    body = response.json()
    assert body["model_version"].startswith("rules-")
    assert [r["id"] for r in body["results"]] == ["a", "b", "c"]
    dining, income, unknown = body["results"]
    assert (dining["category"], dining["method"]) == ("DINING", "rule")
    assert income["category"] == "INCOME"
    assert dining["confidence_calibrated"] is False
    assert dining["model_version"] == body["model_version"]
    assert dining["rule_id"].startswith("dining.")
    assert unknown["category"] == "UNCATEGORIZED"
    assert unknown["method"] == "none"
    assert unknown["confidence"] == 0
    assert unknown["needs_review"] is True
    assert unknown["rule_id"] is None


def test_the_legacy_request_shape_with_only_id_and_description_still_works():
    response = post({"transactions": [{"id": "1", "description": "ZOMATO"}]})

    assert response.status_code == 200
    assert response.json()["results"][0]["category"] == "DINING"


def test_empty_batch_is_valid():
    response = post({"transactions": []})
    assert response.status_code == 200
    assert response.json()["results"] == []


def test_unknown_request_fields_are_ignored():
    response = post({"transactions": [{"id": "1", "description": "ZOMATO", "future_field": 1}], "x": 1})
    assert response.status_code == 200


def test_results_are_deterministic_across_calls():
    body = {"transactions": [{"id": "1", "description": "UPI-RAPIDO-rapido@ybl", "type": "EXPENSE"}]}
    assert post(body).json() == post(body).json()


@pytest.mark.parametrize("body", [
    {},
    {"transactions": "nope"},
    {"transactions": [{"description": "no id"}]},
    {"transactions": [{"id": "1"}]},
    {"transactions": [{"id": "", "description": "x"}]},
    {"transactions": [{"id": "   ", "description": "x"}]},
    {"transactions": [{"id": "x" * 65, "description": "x"}]},
    {"transactions": [{"id": "1", "description": "x", "type": "TRANSFER"}]},
    {"transactions": [{"id": "1", "description": "x", "amount": -5}]},
    {"transactions": [{"id": "1", "description": "x", "amount": "abc"}]},
    {"transactions": [{"id": "1", "description": None}]},
    {"transactions": [{"id": "1", "description": "x" * (MAX_DESCRIPTION + 1)}]},
    {"transactions": [{"id": "dup", "description": "a"}, {"id": "dup", "description": "b"}]},
])
def test_malformed_requests_are_rejected_with_422(body):
    assert post(body).status_code == 422


def test_batches_larger_than_the_cap_are_rejected():
    too_many = [{"id": str(i), "description": "zomato"} for i in range(MAX_BATCH + 1)]
    assert post({"transactions": too_many}).status_code == 422

    at_cap = [{"id": str(i), "description": "zomato"} for i in range(MAX_BATCH)]
    assert post({"transactions": at_cap}).status_code == 200


def test_non_json_body_is_rejected():
    response = client.post("/v1/classify", content=b"not json", headers={"content-type": "application/json"})
    assert response.status_code == 422


def test_get_is_not_allowed():
    assert client.get("/v1/classify").status_code == 405


def test_descriptions_are_not_written_to_the_log(caplog):
    secret = "PRIVATE-DESCRIPTION-MARKER"
    with caplog.at_level("DEBUG"):
        post({"transactions": [{"id": "1", "description": secret}]})
    assert secret not in caplog.text


# ---- Shared-secret authentication ------------------------------------------------------------


def test_with_a_configured_token_requests_must_present_it():
    secured = TestClient(create_app(Settings(internal_token="s3cret")))
    body = {"transactions": [{"id": "1", "description": "zomato"}]}

    assert secured.post("/v1/classify", json=body).status_code == 401
    assert secured.post("/v1/classify", json=body, headers={"X-Internal-Token": "wrong"}).status_code == 401
    ok = secured.post("/v1/classify", json=body, headers={"X-Internal-Token": "s3cret"})
    assert ok.status_code == 200


def test_health_stays_open_when_a_token_is_configured():
    secured = TestClient(create_app(Settings(internal_token="s3cret")))
    assert secured.get("/health").status_code == 200
    assert secured.get("/metrics").status_code == 200


def test_traceparent_is_propagated_and_prometheus_metrics_never_leak_descriptions():
    local_client = TestClient(create_app(Settings()))
    traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    secret_desc = "CONFIDENTIAL-UPI-NARRATION-98765"

    res = local_client.post(
        "/v1/classify",
        json={"transactions": [{"id": "tx-1", "description": secret_desc, "type": "EXPENSE"}]},
        headers={"traceparent": traceparent, "X-Request-Id": "req-obs-42"},
    )
    assert res.status_code == 200
    assert res.headers["traceparent"].startswith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
    assert res.headers["X-Request-Id"] == "req-obs-42"

    metrics_res = local_client.get("/metrics")
    assert metrics_res.status_code == 200
    metrics_text = metrics_res.text
    assert "finguard_ai_classify_batches_total 1" in metrics_text
    assert "finguard_ai_classify_items_total" in metrics_text
    assert secret_desc not in metrics_text


# ---- Recorded examples shared with the Java client's tests -------------------------------------


def test_the_recorded_contract_examples_match_what_the_service_returns():
    examples = json.loads(EXAMPLES.read_text(encoding="utf-8"))

    response = post(examples["request"])

    assert response.status_code == 200
    assert response.json() == examples["response"], (
        "contracts/classify-examples.json is out of date; regenerate it with "
        "`python -m evaluation.export_examples`"
    )
