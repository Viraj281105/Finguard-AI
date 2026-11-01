import copy
import csv
import json
import re
from pathlib import Path

import pytest

from app.classification.engine import RuleBasedClassifier, build_default_classifier
from app.classification.rules import RULES, Rule
from app.classification.taxonomy import Category, TxType
from app.core.config import Settings
from evaluation.evaluate import (
    ADVERSARIAL_PATH,
    CONFIDENCE_TIERS,
    DATA_DIR,
    DATASETS,
    HERE,
    MANIFEST_PATH,
    check_dataset_overlap,
    check_no_real_data_committed,
    compute_adversarial_metrics,
    compute_coverage_accuracy_curve,
    compute_metrics,
    evaluate_candidate_gate,
    load,
    predict,
    run,
    validate_report_provenance,
    verify_manifests,
)

EXPECTED_ATTACK_CATEGORIES = {
    "homoglyphs",
    "truncation",
    "mixed_case",
    "reference_noise",
    "merchant_lookalikes",
    "direction_mismatch",
    "prompt_injection",
}


def test_checked_in_metrics_are_current():
    """The numbers in evaluation/report.md come from metrics.json; it must match the classifier."""
    current, _ = run()
    stored = json.loads((HERE / "metrics.json").read_text(encoding="utf-8"))
    assert current == stored, "run `python -m evaluation.evaluate` and review the diff"
    validate_report_provenance((HERE / "report.md").read_text(encoding="utf-8"), stored)


def test_datasets_are_well_formed_and_labelled_synthetic():
    valid = {c.value for c in Category}
    all_paths = {**DATASETS, "adversarial": ADVERSARIAL_PATH}
    for name, path in all_paths.items():
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        assert rows
        for row in rows:
            assert row["description"].strip()
            assert TxType(row["type"])
            assert row["expected"] in valid
            if name == "adversarial":
                assert row["attack_category"] in EXPECTED_ATTACK_CATEGORIES
    assert "SYNTHETIC" in (HERE / "data" / "README.md").read_text(encoding="utf-8")
    assert "synthetic != real" in (HERE / "data" / "README.md").read_text(encoding="utf-8")
    assert "synthetic != real" in (HERE / "report.md").read_text(encoding="utf-8")


def test_metric_arithmetic_on_a_tiny_known_example():
    rows = [
        {"description": "ZEPTO", "type": "EXPENSE", "expected": "GROCERIES"},       # correct
        {"description": "ZEPTO", "type": "EXPENSE", "expected": "DINING"},          # wrong, asserted
        {"description": "XYZ TRADERS", "type": "EXPENSE", "expected": "SHOPPING"},  # missed (abstain)
        {"description": "XYZ TRADERS", "type": "EXPENSE", "expected": "UNCATEGORIZED"},  # right abstain
    ]
    metrics = compute_metrics(predict(rows))

    assert metrics["rows"] == 4
    assert metrics["accuracy"] == 0.5
    assert metrics["precision_when_classified"] == 0.5
    assert metrics["coverage_of_assignable"] == round(2 / 3, 4)
    assert metrics["recall_on_assignable"] == round(1 / 3, 4)
    assert metrics["wrong_category_asserted"] == 1
    assert metrics["abstention_correct"] == 1.0
    assert metrics["per_class"]["GROCERIES"]["precision"] == 0.5
    assert [c["threshold"] for c in metrics["coverage_accuracy_curve"]] == [t for t, _ in CONFIDENCE_TIERS]


def test_manifest_tamper_detection(tmp_path: Path):
    """verify_manifests fails if SHA-256, row count, or provenance is tampered with."""
    meta = verify_manifests(MANIFEST_PATH, DATA_DIR)
    assert set(meta.keys()) == {"dev", "holdout", "adversarial"}

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    for entry in manifest["datasets"].values():
        src = DATA_DIR / entry["file"]
        (tmp_path / entry["file"]).write_bytes(src.read_bytes())

    # 1. Tamper with sha256
    tampered_sha = copy.deepcopy(manifest)
    tampered_sha["datasets"]["holdout"]["sha256"] = "0" * 64
    sha_path = tmp_path / "manifest_sha.json"
    sha_path.write_text(json.dumps(tampered_sha), encoding="utf-8")
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        verify_manifests(sha_path, tmp_path)

    # 2. Tamper with row_count
    tampered_rows = copy.deepcopy(manifest)
    tampered_rows["datasets"]["dev"]["row_count"] = 999
    rows_path = tmp_path / "manifest_rows.json"
    rows_path.write_text(json.dumps(tampered_rows), encoding="utf-8")
    with pytest.raises(ValueError, match="Row count mismatch"):
        verify_manifests(rows_path, tmp_path)

    # 3. Tamper with data_provenance
    tampered_prov = copy.deepcopy(manifest)
    tampered_prov["datasets"]["adversarial"]["data_provenance"] = "real_bank_export"
    prov_path = tmp_path / "manifest_prov.json"
    prov_path.write_text(json.dumps(tampered_prov), encoding="utf-8")
    with pytest.raises(ValueError, match="Only 'synthetic' datasets may be committed"):
        verify_manifests(prov_path, tmp_path)


def test_real_data_or_pii_detection(tmp_path: Path):
    """check_no_real_data_committed rejects unregistered CSV files and unmasked PII patterns."""
    check_no_real_data_committed(DATA_DIR, MANIFEST_PATH)

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    for entry in manifest["datasets"].values():
        src = DATA_DIR / entry["file"]
        (tmp_path / entry["file"]).write_bytes(src.read_bytes())
    manifest_copy = tmp_path / "manifest.json"
    manifest_copy.write_text(json.dumps(manifest), encoding="utf-8")

    # 1. Add an unregistered CSV file
    extra_csv = tmp_path / "real_hdfc_statement.csv"
    extra_csv.write_text("description,type,expected\nFOO,EXPENSE,DINING\n", encoding="utf-8")
    with pytest.raises(ValueError, match="Unregistered CSV file"):
        check_no_real_data_committed(tmp_path, manifest_copy)
    extra_csv.unlink()

    # 2. Inject an unmasked PAN number into a registered dataset and update its manifest hash/count
    dev_csv = tmp_path / "synthetic_dev.csv"
    with dev_csv.open("a", encoding="utf-8", newline="") as f:
        f.write("PAN ABCDE1234F TAX PAYMENT,EXPENSE,TAXES\n")
    from evaluation.evaluate import compute_file_sha256

    manifest["datasets"]["dev"]["sha256"] = compute_file_sha256(dev_csv)
    manifest["datasets"]["dev"]["row_count"] = len(load(dev_csv))
    manifest_copy.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="Potential unmasked PAN pattern"):
        check_no_real_data_committed(tmp_path, manifest_copy)


def test_dev_holdout_overlap_detection():
    """check_dataset_overlap passes on clean datasets and catches exact or normalized leakage."""
    dev_rows = load(DATASETS["dev"])
    holdout_rows = load(DATASETS["holdout"])
    check_dataset_overlap(dev_rows, holdout_rows)

    # Exact duplicate injection
    leaked_dev = dev_rows + [holdout_rows[0]]
    with pytest.raises(ValueError, match="exact overlap"):
        check_dataset_overlap(leaked_dev, holdout_rows)

    # Normalized duplicate injection (different reference number and bank handle)
    norm_leaked_dev = dev_rows + [
        {
            "description": "UPI-ZOMATO-zomato@okaxis-999999999999-ORDER",
            "type": "EXPENSE",
            "expected": "DINING",
        }
    ]
    with pytest.raises(ValueError, match="normalised overlap"):
        check_dataset_overlap(norm_leaked_dev, holdout_rows)


def test_coverage_accuracy_curve_and_adversarial_suite():
    """Verify confidence-tier curve monotonicity and 7-category adversarial coverage."""
    holdout_preds = predict(load(DATASETS["holdout"]))
    curve = compute_coverage_accuracy_curve(holdout_preds)
    by_thresh = {row["threshold"]: row for row in curve}
    assert by_thresh[0.90]["coverage_of_assignable"] <= by_thresh[0.75]["coverage_of_assignable"]
    assert by_thresh[0.75]["coverage_of_assignable"] <= by_thresh[0.55]["coverage_of_assignable"]
    assert by_thresh[0.55]["precision_when_classified"] == 1.0

    adv_preds = predict(load(ADVERSARIAL_PATH))
    adv_metrics = compute_adversarial_metrics(adv_preds)
    assert adv_metrics["rows"] == 28
    assert adv_metrics["wrong_category_asserted"] == 0
    assert adv_metrics["accuracy"] == 1.0
    assert set(adv_metrics["by_attack_category"].keys()) == EXPECTED_ATTACK_CATEGORIES


def test_mutation_regression_gate_catches_bad_rule():
    """A deliberately bad rule (broad 'upi' catch-all) must be caught by evaluate_candidate_gate."""
    baseline_metrics, _ = run(build_default_classifier("rules-1.0.0"))
    baseline_gate = evaluate_candidate_gate(baseline_metrics, baseline_metrics)
    assert baseline_gate.passed
    assert baseline_gate.failures == ()

    # Mutated classifier with an overly broad 'upi' rule at high priority
    bad_rules = (
        Rule(
            id="bad-upi-catchall",
            category=Category.SHOPPING,
            label="upi",
            pattern=re.compile(r"(?<![a-z0-9])upi(?![a-z0-9])"),
            strength=0.90,
            priority=100,
        ),
        *RULES,
    )
    mutated_classifier = RuleBasedClassifier(rules=bad_rules, version="rules-mutated-bad")
    candidate_metrics, _ = run(mutated_classifier)
    gate_result = evaluate_candidate_gate(baseline_metrics, candidate_metrics)
    assert not gate_result.passed, "Mutation regression gate must fail on broad 'upi' rule"
    assert any("holdout" in f or "adversarial" in f for f in gate_result.failures)


def test_classifier_chain_rollback_drill():
    """Switching AI_CLASSIFIER_CHAIN from a degraded candidate back to rules-1.0.0 restores outputs bit-for-bit."""
    holdout_rows = load(DATASETS["holdout"])
    adv_rows = load(ADVERSARIAL_PATH)

    baseline_settings = Settings(classifier_chain="rules-1.0.0")
    baseline_clf = build_default_classifier(baseline_settings.classifier_chain)
    baseline_holdout = predict(holdout_rows, classifier=baseline_clf)
    baseline_adv = predict(adv_rows, classifier=baseline_clf)
    baseline_metrics, _ = run(baseline_clf)

    # Switch to degraded legacy/candidate chain rules-0.9.0
    candidate_settings = Settings(classifier_chain="rules-0.9.0")
    candidate_clf = build_default_classifier(candidate_settings.classifier_chain)
    candidate_holdout = predict(holdout_rows, classifier=candidate_clf)
    candidate_metrics, _ = run(candidate_clf)
    assert candidate_metrics["holdout"]["accuracy"] < 1.0
    assert not evaluate_candidate_gate(baseline_metrics, candidate_metrics).passed
    assert candidate_holdout != baseline_holdout

    # Rollback to rules-1.0.0 via chain_spec
    rollback_settings = Settings(classifier_chain="rules-1.0.0")
    rollback_clf = build_default_classifier(rollback_settings.classifier_chain)
    assert rollback_clf.version == "rules-1.0.0"
    assert predict(holdout_rows, classifier=rollback_clf) == baseline_holdout
    assert predict(adv_rows, classifier=rollback_clf) == baseline_adv

    with pytest.raises(ValueError, match="Unknown classifier version"):
        build_default_classifier("rules-9.9.9-nonexistent")


def test_report_provenance_stamp_validation():
    """validate_report_provenance fails if report.md or metrics.json lacks synthetic stamps."""
    valid_report = (HERE / "report.md").read_text(encoding="utf-8")
    valid_metrics = json.loads((HERE / "metrics.json").read_text(encoding="utf-8"))
    validate_report_provenance(valid_report, valid_metrics)

    with pytest.raises(ValueError, match="Evaluation report must be titled"):
        validate_report_provenance("# Evaluation Report\nsynthetic != real\n", valid_metrics)

    with pytest.raises(ValueError, match="must state 'synthetic != real'"):
        validate_report_provenance("# SYNTHETIC — Report\nNo disclaimer here.\n", valid_metrics)

    bad_metrics = {"dev": {"data_provenance": "real"}}
    with pytest.raises(ValueError, match="missing data_provenance='synthetic'"):
        validate_report_provenance(valid_report, bad_metrics)
