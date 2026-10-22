r"""Scores the classifier on the labelled synthetic CSV files and writes ``metrics.json`` + ``report.md``.

    cd ai
    .\.venv\Scripts\python -m evaluation.evaluate          # rewrite the report
    .\.venv\Scripts\python -m evaluation.evaluate --check  # exit 1 if metrics.json is stale

The data is synthetic (see data/README.md); the report says so and is the only source for any
accuracy statement in the documentation. Metrics are deterministic; latency is measured on the
machine that generates the report and is therefore kept out of ``metrics.json``.
"""

import argparse
import csv
import json
import statistics
import sys
import time
from collections import Counter
from decimal import Decimal
from pathlib import Path

from app.classification.base import ClassificationInput, Method
from app.classification.engine import build_default_classifier
from app.classification.taxonomy import Category, TxType

HERE = Path(__file__).resolve().parent
DATASETS = {"dev": HERE / "data" / "synthetic_dev.csv", "holdout": HERE / "data" / "synthetic_holdout.csv"}
ABSTAIN = Category.UNCATEGORIZED.value


def load(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def predict(rows: list[dict[str, str]]) -> list[dict[str, object]]:
    classifier = build_default_classifier()
    out = []
    for index, row in enumerate(rows):
        result = classifier.classify(
            ClassificationInput(str(index), row["description"], TxType(row["type"]))
        )
        out.append({
            "description": row["description"],
            "type": row["type"],
            "expected": row["expected"],
            "predicted": result.category.value,
            "abstained": result.method is Method.NONE,
            "needs_review": result.needs_review,
            "rule_id": result.rule_id,
        })
    return out


def _ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _f1(precision: float | None, recall: float | None) -> float | None:
    if precision is None or recall is None or precision + recall == 0:
        return 0.0 if precision is not None and recall is not None else None
    return round(2 * precision * recall / (precision + recall), 4)


def compute_metrics(predictions: list[dict[str, object]]) -> dict[str, object]:
    n = len(predictions)
    correct = sum(p["expected"] == p["predicted"] for p in predictions)
    classified = [p for p in predictions if not p["abstained"]]
    classified_correct = sum(p["expected"] == p["predicted"] for p in classified)
    assignable = [p for p in predictions if p["expected"] != ABSTAIN]
    assignable_correct = sum(p["expected"] == p["predicted"] for p in assignable)
    should_abstain = [p for p in predictions if p["expected"] == ABSTAIN]

    flagged = [p for p in classified if p["needs_review"]]
    unflagged = [p for p in classified if not p["needs_review"]]

    labels = sorted({str(p["expected"]) for p in predictions} - {ABSTAIN})
    per_class: dict[str, dict[str, object]] = {}
    for label in labels:
        tp = sum(p["expected"] == label and p["predicted"] == label for p in predictions)
        predicted = sum(p["predicted"] == label for p in predictions)
        support = sum(p["expected"] == label for p in predictions)
        precision, recall = _ratio(tp, predicted), _ratio(tp, support)
        per_class[label] = {"support": support, "precision": precision, "recall": recall,
                            "f1": _f1(precision, recall)}
    f1_values = [c["f1"] for c in per_class.values() if c["f1"] is not None]

    columns = sorted({str(p["predicted"]) for p in predictions} | set(labels) | {ABSTAIN})
    matrix = {
        expected: dict(Counter(str(p["predicted"]) for p in predictions if p["expected"] == expected))
        for expected in sorted({str(p["expected"]) for p in predictions})
    }

    errors = [
        {k: p[k] for k in ("description", "type", "expected", "predicted")}
        for p in predictions if p["expected"] != p["predicted"]
    ]
    return {
        "rows": n,
        "accuracy": _ratio(correct, n),
        "coverage_of_assignable": _ratio(sum(not p["abstained"] for p in assignable), len(assignable)),
        "precision_when_classified": _ratio(classified_correct, len(classified)),
        "recall_on_assignable": _ratio(assignable_correct, len(assignable)),
        "abstention_correct": _ratio(sum(p["abstained"] for p in should_abstain), len(should_abstain)),
        "wrong_category_asserted": sum(
            not p["abstained"] and p["expected"] != p["predicted"] for p in predictions
        ),
        "macro_f1": round(statistics.mean(f1_values), 4) if f1_values else None,
        "review_flag": {
            "flagged": len(flagged),
            "precision_when_flagged": _ratio(
                sum(p["expected"] == p["predicted"] for p in flagged), len(flagged)),
            "unflagged": len(unflagged),
            "precision_when_unflagged": _ratio(
                sum(p["expected"] == p["predicted"] for p in unflagged), len(unflagged)),
        },
        "per_class": per_class,
        "confusion_columns": columns,
        "confusion": matrix,
        "errors": errors,
    }


def measure_latency(rows: list[dict[str, str]], batch: int = 200, repeats: int = 20) -> dict[str, float]:
    classifier = build_default_classifier()
    items = [
        ClassificationInput(str(i), rows[i % len(rows)]["description"],
                            TxType(rows[i % len(rows)]["type"]), Decimal(100))
        for i in range(batch)
    ]
    timings = []
    for _ in range(repeats):
        start = time.perf_counter()
        for item in items:
            classifier.classify(item)
        timings.append((time.perf_counter() - start) * 1000)
    return {"batch_size": batch, "median_ms": round(statistics.median(timings), 2),
            "max_ms": round(max(timings), 2)}


def _pct(value: float | None) -> str:
    return "n/a" if value is None else f"{value * 100:.1f}%"


def render_report(metrics: dict[str, dict[str, object]], latency: dict[str, float]) -> str:
    classifier = build_default_classifier()
    lines = [
        "# Classifier evaluation report",
        "",
        "> **Read this first.** The data is **synthetic**: written by the project's developers, not taken from",
        "> real bank statements. The same author wrote the rules and the data, so these figures are",
        "> **optimistic** and are **not** an estimate of accuracy on real statements. The classifier is a",
        "> deterministic rule baseline; **no machine-learning model was trained**, so there is no model metric.",
        "> `confidence` is a rule-strength tier, not a calibrated probability.",
        "",
        (
            f"Classifier under test: `{classifier.version}` (method: rules). Regenerate with "
            "`cd ai; .\\.venv\\Scripts\\python -m evaluation.evaluate`."
        ),
        "",
        "## Summary",
        "",
        "| Metric | " + " | ".join(f"`{name}`" for name in metrics) + " |",
        "|---|" + "---:|" * len(metrics),
    ]
    rows = [
        ("Rows", "rows", str),
        ("Accuracy (an abstention is correct when the label is UNCATEGORIZED)", "accuracy", _pct),
        ("Coverage: labelled rows that received a category", "coverage_of_assignable", _pct),
        ("Precision when a category was asserted", "precision_when_classified", _pct),
        ("Recall on labelled rows", "recall_on_assignable", _pct),
        ("Abstained where the label was UNCATEGORIZED", "abstention_correct", _pct),
        ("Macro-F1 over labelled categories", "macro_f1", _pct),
        ("Wrong category asserted (count)", "wrong_category_asserted", str),
    ]
    for title, key, fmt in rows:
        lines.append(f"| {title} | " + " | ".join(fmt(m[key]) for m in metrics.values()) + " |")
    for state, key in (("flagged", "flagged"), ("not flagged", "unflagged")):
        lines.append(
            f"| Rows asserted and {state} for review (precision) | "
            + " | ".join(
                f"{m['review_flag'][key]} ({_pct(m['review_flag']['precision_when_' + key])})"
                for m in metrics.values()
            ) + " |"
        )
    lines += [
        "",
        (
            f"Latency (in-process, {latency['batch_size']} items, median of 20 runs): "
            f"**{latency['median_ms']} ms** (max {latency['max_ms']} ms). Measured on the machine "
            "that generated this report; it excludes HTTP and JSON overhead."
        ),
    ]

    for name, m in metrics.items():
        lines += ["", f"## `{name}` — per-category results", "",
                  "| Category | Support | Precision | Recall | F1 |", "|---|---:|---:|---:|---:|"]
        for label, c in m["per_class"].items():
            lines.append(f"| {label} | {c['support']} | {_pct(c['precision'])} | "
                         f"{_pct(c['recall'])} | {_pct(c['f1'])} |")
        lines += ["", f"### `{name}` — errors ({len(m['errors'])})", ""]
        if m["errors"]:
            lines += ["| Description | Type | Expected | Predicted |", "|---|---|---|---|"]
            for e in m["errors"]:
                lines.append(f"| `{e['description']}` | {e['type']} | {e['expected']} | {e['predicted']} |")
        else:
            lines.append("None.")

        columns = m["confusion_columns"]
        lines += ["", f"### `{name}` — confusion matrix (rows: expected, columns: predicted)", "",
                  "| expected \\ predicted | " + " | ".join(columns) + " |",
                  "|---|" + "---:|" * len(columns)]
        for expected, counts in m["confusion"].items():
            lines.append(f"| {expected} | " + " | ".join(str(counts.get(c, "")) for c in columns) + " |")

    lines += [
        "",
        "## Changes made after reading the held-out errors",
        "",
        "See the section at the end of this file, maintained by hand when rules change.",
        "",
    ]
    return "\n".join(lines)


def run() -> tuple[dict[str, dict[str, object]], dict[str, float]]:
    metrics = {name: compute_metrics(predict(load(path))) for name, path in DATASETS.items()}
    return metrics, measure_latency(load(DATASETS["holdout"]))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if metrics.json is out of date")
    args = parser.parse_args()

    metrics, latency = run()
    metrics_path = HERE / "metrics.json"
    serialised = json.dumps(metrics, indent=2, sort_keys=True) + "\n"

    if args.check:
        if not metrics_path.exists() or metrics_path.read_text(encoding="utf-8") != serialised:
            print("evaluation/metrics.json is stale; run `python -m evaluation.evaluate`", file=sys.stderr)
            return 1
        return 0

    metrics_path.write_text(serialised, encoding="utf-8")
    report_path = HERE / "report.md"
    notes = ""
    if report_path.exists():
        existing = report_path.read_text(encoding="utf-8")
        marker = "<!-- manual-notes -->"
        if marker in existing:
            notes = existing[existing.index(marker):]
    report = render_report(metrics, latency).rstrip("\n") + "\n\n" + (
        notes or "<!-- manual-notes -->\n(none yet)\n")
    report_path.write_text(report, encoding="utf-8")
    holdout = metrics["holdout"]
    print(f"holdout: accuracy={_pct(holdout['accuracy'])} errors={len(holdout['errors'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
