import csv
import json

from app.classification.taxonomy import Category, TxType
from evaluation.evaluate import DATASETS, HERE, compute_metrics, load, predict


def test_checked_in_metrics_are_current():
    """The numbers in evaluation/report.md come from metrics.json; it must match the classifier."""
    current = {name: compute_metrics(predict(load(path))) for name, path in DATASETS.items()}
    stored = json.loads((HERE / "metrics.json").read_text(encoding="utf-8"))
    assert current == stored, "run `python -m evaluation.evaluate` and review the diff"


def test_datasets_are_well_formed_and_labelled_synthetic():
    valid = {c.value for c in Category}
    for path in DATASETS.values():
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        assert rows
        for row in rows:
            assert row["description"].strip()
            assert TxType(row["type"])
            assert row["expected"] in valid
    assert "SYNTHETIC" in (HERE / "data" / "README.md").read_text(encoding="utf-8")
    assert "synthetic" in (HERE / "report.md").read_text(encoding="utf-8").lower()


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
