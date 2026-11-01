r"""Scores the classifier on the labelled synthetic CSV files and writes ``metrics.json`` + ``report.md``.

    cd ai
    .\.venv\Scripts\python -m evaluation.evaluate          # rewrite the report
    .\.venv\Scripts\python -m evaluation.evaluate --check  # exit 1 if manifests, overlap, provenance, or metrics fail

The data is synthetic (see data/README.md); synthetic != real. The report is titled ``SYNTHETIC —``
and is the only source for any synthetic evaluation statement in the documentation. Metrics are
deterministic; latency is measured on the machine that generates the report and is therefore kept
out of ``metrics.json``.
"""

import argparse
import csv
import hashlib
import json
import re
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

from app.classification.base import ClassificationInput, Classifier, Method
from app.classification.engine import build_default_classifier
from app.classification.normalize import normalize
from app.classification.taxonomy import Category, TxType

HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "data"
MANIFEST_PATH = DATA_DIR / "manifest.json"
DATASETS = {
    "dev": DATA_DIR / "synthetic_dev.csv",
    "holdout": DATA_DIR / "synthetic_holdout.csv",
}
ADVERSARIAL_PATH = DATA_DIR / "synthetic_adversarial.csv"
ABSTAIN = Category.UNCATEGORIZED.value

CONFIDENCE_TIERS: tuple[tuple[float, str], ...] = (
    (0.90, ">= 0.90 (strong)"),
    (0.75, ">= 0.75 (medium+)"),
    (0.55, ">= 0.55 (all asserted)"),
)

# Patterns that must never appear in committed synthetic evaluation CSVs (PII / real data guard).
_PAN_RE = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
_AADHAAR_GROUPED_RE = re.compile(r"\b[2-9][0-9]{3}\s[0-9]{4}\s[0-9]{4}\b")
_UNMASKED_CARD_RE = re.compile(r"\b[45][0-9]{15}\b")


@dataclass(frozen=True)
class GateResult:
    passed: bool
    failures: tuple[str, ...]


def load(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def compute_file_sha256(path: Path) -> str:
    """Computes SHA-256 over canonical LF-normalised UTF-8 bytes so Windows and Linux match."""
    canonical = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_manifests(
    manifest_path: Path = MANIFEST_PATH,
    data_dir: Path = DATA_DIR,
) -> dict[str, dict[str, object]]:
    """Verifies dataset manifests, SHA-256 digests, row counts, and synthetic provenance."""
    if not manifest_path.exists():
        raise ValueError(f"Dataset manifest missing: {manifest_path}")

    manifest_doc = json.loads(manifest_path.read_text(encoding="utf-8"))
    datasets_meta = manifest_doc.get("datasets")
    if not isinstance(datasets_meta, dict) or not datasets_meta:
        raise ValueError("manifest.json must contain a non-empty 'datasets' object")

    required_keys = {
        "dataset_id",
        "file",
        "data_provenance",
        "sha256",
        "row_count",
        "label_procedure",
        "frozen_holdout",
    }

    for split_name, meta in datasets_meta.items():
        missing = required_keys - set(meta.keys())
        if missing:
            raise ValueError(f"Manifest entry '{split_name}' missing keys: {sorted(missing)}")

        if meta["data_provenance"] != "synthetic":
            raise ValueError(
                f"Manifest entry '{split_name}' has data_provenance='{meta['data_provenance']}'. "
                "Only 'synthetic' datasets may be committed to Git."
            )

        csv_path = data_dir / str(meta["file"])
        if not csv_path.exists():
            raise ValueError(f"Dataset file for '{split_name}' not found: {csv_path}")

        actual_sha = compute_file_sha256(csv_path)
        if actual_sha != meta["sha256"]:
            raise ValueError(
                f"SHA-256 mismatch for '{split_name}' ({csv_path.name}): "
                f"manifest={meta['sha256']} actual={actual_sha}"
            )

        rows = load(csv_path)
        if len(rows) != meta["row_count"]:
            raise ValueError(
                f"Row count mismatch for '{split_name}' ({csv_path.name}): "
                f"manifest={meta['row_count']} actual={len(rows)}"
            )

    return datasets_meta


def check_no_real_data_committed(
    data_dir: Path = DATA_DIR,
    manifest_path: Path = MANIFEST_PATH,
) -> None:
    """Ensures every CSV in data_dir is registered as synthetic and free of unmasked PII patterns."""
    datasets_meta = verify_manifests(manifest_path=manifest_path, data_dir=data_dir)
    registered_files = {str(m["file"]) for m in datasets_meta.values()}

    for csv_file in sorted(data_dir.glob("*.csv")):
        if csv_file.name not in registered_files:
            raise ValueError(
                f"Unregistered CSV file '{csv_file.name}' in {data_dir}; "
                "all committed datasets must be declared in manifest.json with data_provenance='synthetic'."
            )
        for row_idx, row in enumerate(load(csv_file), start=1):
            desc = row.get("description", "")
            if _PAN_RE.search(desc):
                raise ValueError(f"Potential unmasked PAN pattern in {csv_file.name} row {row_idx}")
            if _AADHAAR_GROUPED_RE.search(desc):
                raise ValueError(f"Potential unmasked Aadhaar pattern in {csv_file.name} row {row_idx}")
            if _UNMASKED_CARD_RE.search(desc):
                raise ValueError(f"Potential unmasked 16-digit card number in {csv_file.name} row {row_idx}")


def check_dataset_overlap(
    dev_rows: list[dict[str, str]],
    holdout_rows: list[dict[str, str]],
) -> None:
    """Fails if dev and holdout share any exact or normalised multi-token transaction."""
    dev_exact: set[tuple[str, str]] = set()
    dev_norm: dict[tuple[str, str], str] = {}

    for row in dev_rows:
        raw_key = (row["description"].strip().lower(), row["type"].strip().upper())
        dev_exact.add(raw_key)
        norm_text = normalize(row["description"]).text
        # Bare single-token channel strings (e.g. 'upi' after stripping 10-digit phone numbers)
        # are channel templates rather than duplicate merchant narrations.
        if len(norm_text.split()) >= 2:
            dev_norm[(norm_text, row["type"].strip().upper())] = row["description"]

    overlaps: list[str] = []
    for row in holdout_rows:
        raw_key = (row["description"].strip().lower(), row["type"].strip().upper())
        if raw_key in dev_exact:
            overlaps.append(f"exact overlap: ({row['description']!r}, {row['type']})")
            continue
        norm_text = normalize(row["description"]).text
        norm_key = (norm_text, row["type"].strip().upper())
        if len(norm_text.split()) >= 2 and norm_key in dev_norm:
            overlaps.append(
                f"normalised overlap ({norm_text!r}): dev={dev_norm[norm_key]!r} vs holdout={row['description']!r}"
            )

    if overlaps:
        raise ValueError("Train/holdout overlap detected: " + "; ".join(overlaps))


def predict(
    rows: list[dict[str, str]],
    classifier: Classifier | None = None,
) -> list[dict[str, object]]:
    classifier = classifier or build_default_classifier()
    out = []
    for index, row in enumerate(rows):
        result = classifier.classify(
            ClassificationInput(str(index), row["description"], TxType(row["type"]))
        )
        entry: dict[str, object] = {
            "description": row["description"],
            "type": row["type"],
            "expected": row["expected"],
            "predicted": result.category.value,
            "confidence": result.confidence,
            "abstained": result.method is Method.NONE,
            "needs_review": result.needs_review,
            "rule_id": result.rule_id,
        }
        if "attack_category" in row:
            entry["attack_category"] = row["attack_category"]
        out.append(entry)
    return out


def _ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def _f1(precision: float | None, recall: float | None) -> float | None:
    if precision is None or recall is None or precision + recall == 0:
        return 0.0 if precision is not None and recall is not None else None
    return round(2 * precision * recall / (precision + recall), 4)


def compute_coverage_accuracy_curve(
    predictions: list[dict[str, object]],
) -> list[dict[str, object]]:
    """Computes coverage, abstention rate, and precision at each confidence threshold tier."""
    n = len(predictions)
    assignable = [p for p in predictions if p["expected"] != ABSTAIN]
    curve: list[dict[str, object]] = []

    for threshold, tier_label in CONFIDENCE_TIERS:
        asserted_at_tier = [
            p for p in predictions
            if not p["abstained"] and float(p.get("confidence", 0.0)) >= threshold
        ]
        assignable_asserted = [
            p for p in assignable
            if not p["abstained"] and float(p.get("confidence", 0.0)) >= threshold
        ]
        correct_asserted = sum(p["expected"] == p["predicted"] for p in asserted_at_tier)
        wrong_asserted = sum(p["expected"] != p["predicted"] for p in asserted_at_tier)
        abstained_count = n - len(asserted_at_tier)

        curve.append({
            "threshold": threshold,
            "tier": tier_label,
            "asserted": len(asserted_at_tier),
            "coverage_of_assignable": _ratio(len(assignable_asserted), len(assignable)),
            "abstention_rate": _ratio(abstained_count, n),
            "precision_when_classified": _ratio(correct_asserted, len(asserted_at_tier)),
            "wrong_category_asserted": wrong_asserted,
        })
    return curve


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
        "data_provenance": "synthetic",
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
        "coverage_accuracy_curve": compute_coverage_accuracy_curve(predictions),
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


def compute_adversarial_metrics(predictions: list[dict[str, object]]) -> dict[str, object]:
    """Scores the classifier on the 7 adversarial perturbation categories."""
    base = compute_metrics(predictions)
    categories = sorted({str(p.get("attack_category", "other")) for p in predictions})
    by_attack: dict[str, dict[str, object]] = {}
    for cat in categories:
        subset = [p for p in predictions if p.get("attack_category") == cat]
        passed = sum(p["expected"] == p["predicted"] for p in subset)
        wrong_asserted = sum(
            not p["abstained"] and p["expected"] != p["predicted"] for p in subset
        )
        by_attack[cat] = {
            "rows": len(subset),
            "passed": passed,
            "pass_rate": _ratio(passed, len(subset)),
            "wrong_category_asserted": wrong_asserted,
        }
    base["by_attack_category"] = by_attack
    return base


def evaluate_candidate_gate(
    baseline_metrics: dict[str, dict[str, object]],
    candidate_metrics: dict[str, dict[str, object]],
    *,
    max_coverage_drop: float = 0.02,
    category_precision_floor: float = 0.95,
) -> GateResult:
    """Evaluates a candidate classifier against the baseline regression & safety gate."""
    failures: list[str] = []

    for split in ("dev", "holdout", "adversarial"):
        if split not in baseline_metrics or split not in candidate_metrics:
            continue
        base_s = baseline_metrics[split]
        cand_s = candidate_metrics[split]

        base_wrong = int(base_s.get("wrong_category_asserted", 0))
        cand_wrong = int(cand_s.get("wrong_category_asserted", 0))
        if cand_wrong > base_wrong:
            failures.append(
                f"{split}: wrong_category_asserted increased from {base_wrong} to {cand_wrong}"
            )

        base_prec = base_s.get("precision_when_classified")
        cand_prec = cand_s.get("precision_when_classified")
        if (
            isinstance(base_prec, (int, float))
            and isinstance(cand_prec, (int, float))
            and cand_prec < base_prec
        ):
            failures.append(
                f"{split}: precision_when_classified regressed from {base_prec:.4f} to {cand_prec:.4f}"
            )

        base_abs = base_s.get("abstention_correct")
        cand_abs = cand_s.get("abstention_correct")
        if (
            isinstance(base_abs, (int, float))
            and isinstance(cand_abs, (int, float))
            and cand_abs < base_abs
        ):
            failures.append(
                f"{split}: abstention_correct regressed from {base_abs:.4f} to {cand_abs:.4f}"
            )

        base_cov = base_s.get("coverage_of_assignable")
        cand_cov = cand_s.get("coverage_of_assignable")
        if (
            isinstance(base_cov, (int, float))
            and isinstance(cand_cov, (int, float))
            and (base_cov - cand_cov) > max_coverage_drop
        ):
            failures.append(
                f"{split}: coverage_of_assignable dropped by {base_cov - cand_cov:.4f} "
                f"(max allowed drop {max_coverage_drop:.4f})"
            )

    if "holdout" in candidate_metrics:
        per_class = candidate_metrics["holdout"].get("per_class", {})
        if isinstance(per_class, dict):
            for cat, stats in per_class.items():
                prec = stats.get("precision")
                if isinstance(prec, (int, float)) and prec < category_precision_floor:
                    failures.append(
                        f"holdout: category {cat} precision {prec:.4f} below floor {category_precision_floor:.4f}"
                    )

    if "adversarial" in baseline_metrics and "adversarial" in candidate_metrics:
        base_attacks = baseline_metrics["adversarial"].get("by_attack_category", {})
        cand_attacks = candidate_metrics["adversarial"].get("by_attack_category", {})
        if isinstance(base_attacks, dict) and isinstance(cand_attacks, dict):
            for attack_cat, base_info in base_attacks.items():
                cand_info = cand_attacks.get(attack_cat, {})
                b_rate = base_info.get("pass_rate")
                c_rate = cand_info.get("pass_rate")
                if isinstance(b_rate, (int, float)) and isinstance(c_rate, (int, float)) and c_rate < b_rate:
                    failures.append(
                        f"adversarial.{attack_cat}: pass_rate regressed from {b_rate:.4f} to {c_rate:.4f}"
                    )

    return GateResult(passed=not failures, failures=tuple(failures))


def validate_report_provenance(
    report_text: str,
    metrics: dict[str, dict[str, object]],
) -> None:
    """Verifies that synthetic evaluation artifacts carry explicit provenance stamps."""
    if not report_text.startswith("# SYNTHETIC —"):
        raise ValueError("Evaluation report must be titled '# SYNTHETIC — ...'")
    if "synthetic != real" not in report_text.lower() and "synthetic ≠ real" not in report_text.lower():
        raise ValueError("Evaluation report must state 'synthetic != real' (or 'synthetic ≠ real')")
    if "## Coverage vs. Accuracy by Confidence Tier" not in report_text:
        raise ValueError("Evaluation report missing '## Coverage vs. Accuracy by Confidence Tier' section")
    if "## Adversarial Robustness Suite" not in report_text:
        raise ValueError("Evaluation report missing '## Adversarial Robustness Suite' section")

    for split_name, split_metrics in metrics.items():
        if split_metrics.get("data_provenance") != "synthetic":
            raise ValueError(f"metrics['{split_name}'] missing data_provenance='synthetic' stamp")


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
    manifest_meta = verify_manifests()
    primary_splits = {k: metrics[k] for k in ("dev", "holdout") if k in metrics}

    lines = [
        "# SYNTHETIC — Classifier evaluation report",
        "",
        "> **PROVENANCE STAMP (`data_provenance: synthetic` · synthetic ≠ real).**",
        "> Every dataset scored in this report is **synthetic**: written by the project's developers, not taken from",
        "> real bank statements. The same author wrote the rules and the data, so these figures are",
        "> **optimistic** and are **not** an estimate of accuracy on real statements (`synthetic != real`).",
        "> The classifier is a deterministic rule baseline; **no machine-learning model was trained**, so there",
        "> is no model metric. `confidence` is a rule-strength tier, not a calibrated probability.",
        "",
        (
            f"Classifier under test: `{classifier.version}` (method: rules). Regenerate with "
            "`cd ai; .\\.venv\\Scripts\\python -m evaluation.evaluate`."
        ),
        "",
        "## Dataset Manifests & Provenance",
        "",
        "| Split | Dataset ID | File | Provenance | Rows | Frozen Holdout | SHA-256 |",
        "|---|---|---|---|---:|---|---|",
    ]
    for split_name, meta in manifest_meta.items():
        sha_short = str(meta["sha256"])[:16] + "…"
        lines.append(
            f"| `{split_name}` | `{meta['dataset_id']}` | `{meta['file']}` | "
            f"`{meta['data_provenance']}` | {meta['row_count']} | "
            f"`{meta['frozen_holdout']}` | `{sha_short}` |"
        )

    lines += [
        "",
        "## Summary",
        "",
        "| Metric | " + " | ".join(f"`{name}`" for name in primary_splits) + " |",
        "|---|" + "---:|" * len(primary_splits),
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
        lines.append(f"| {title} | " + " | ".join(fmt(m[key]) for m in primary_splits.values()) + " |")
    for state, key in (("flagged", "flagged"), ("not flagged", "unflagged")):
        lines.append(
            f"| Rows asserted and {state} for review (precision) | "
            + " | ".join(
                f"{m['review_flag'][key]} ({_pct(m['review_flag']['precision_when_' + key])})"
                for m in primary_splits.values()
            ) + " |"
        )
    lines += [
        "",
        (
            f"Latency (in-process, {latency['batch_size']} items, median of 20 runs): "
            f"**{latency['median_ms']} ms** (max {latency['max_ms']} ms). Measured on the machine "
            "that generated this report; it excludes HTTP and JSON overhead."
        ),
        "",
        "## Coverage vs. Accuracy by Confidence Tier",
        "",
        "| Split | Confidence Tier | Asserted Rows | Coverage of Assignable | Abstention Rate | Precision on Asserted | Wrong Category Asserted |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]
    for split_name, m in primary_splits.items():
        for tier_row in m.get("coverage_accuracy_curve", []):
            lines.append(
                f"| `{split_name}` | `{tier_row['tier']}` | {tier_row['asserted']} | "
                f"{_pct(tier_row['coverage_of_assignable'])} | {_pct(tier_row['abstention_rate'])} | "
                f"{_pct(tier_row['precision_when_classified'])} | {tier_row['wrong_category_asserted']} |"
            )

    if "adversarial" in metrics:
        adv = metrics["adversarial"]
        lines += [
            "",
            "## Adversarial Robustness Suite",
            "",
            (
                f"Total adversarial cases: **{adv['rows']}** · Overall accuracy: **{_pct(adv['accuracy'])}** · "
                f"Wrong categories asserted: **{adv['wrong_category_asserted']}** · "
                f"Abstention on uncategorisable attacks: **{_pct(adv['abstention_correct'])}**."
            ),
            "",
            "| Attack Category | Cases | Passed | Pass Rate | Wrong Category Asserted |",
            "|---|---:|---:|---:|---:|",
        ]
        for attack_cat, info in adv.get("by_attack_category", {}).items():
            lines.append(
                f"| `{attack_cat}` | {info['rows']} | {info['passed']} | "
                f"{_pct(info['pass_rate'])} | {info['wrong_category_asserted']} |"
            )

    for name, m in primary_splits.items():
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


def run(
    classifier: Classifier | None = None,
) -> tuple[dict[str, dict[str, object]], dict[str, float]]:
    verify_manifests()
    check_no_real_data_committed()
    dev_rows = load(DATASETS["dev"])
    holdout_rows = load(DATASETS["holdout"])
    check_dataset_overlap(dev_rows, holdout_rows)

    metrics: dict[str, dict[str, object]] = {
        name: compute_metrics(predict(load(path), classifier=classifier))
        for name, path in DATASETS.items()
    }
    if ADVERSARIAL_PATH.exists():
        metrics["adversarial"] = compute_adversarial_metrics(
            predict(load(ADVERSARIAL_PATH), classifier=classifier)
        )
    return metrics, measure_latency(holdout_rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if manifests, overlap, provenance stamps, or metrics.json are out of date",
    )
    args = parser.parse_args()

    try:
        metrics, latency = run()
    except ValueError as exc:
        print(f"evaluation governance check failed: {exc}", file=sys.stderr)
        return 1

    metrics_path = HERE / "metrics.json"
    report_path = HERE / "report.md"
    serialised = json.dumps(metrics, indent=2, sort_keys=True) + "\n"

    if args.check:
        if not metrics_path.exists() or metrics_path.read_text(encoding="utf-8") != serialised:
            print("evaluation/metrics.json is stale; run `python -m evaluation.evaluate`", file=sys.stderr)
            return 1
        if not report_path.exists():
            print("evaluation/report.md is missing; run `python -m evaluation.evaluate`", file=sys.stderr)
            return 1
        try:
            validate_report_provenance(report_path.read_text(encoding="utf-8"), metrics)
        except ValueError as exc:
            print(f"evaluation provenance check failed: {exc}", file=sys.stderr)
            return 1
        return 0

    metrics_path.write_text(serialised, encoding="utf-8")
    notes = ""
    if report_path.exists():
        existing = report_path.read_text(encoding="utf-8")
        marker = "<!-- manual-notes -->"
        if marker in existing:
            notes = existing[existing.index(marker):]
    report = render_report(metrics, latency).rstrip("\n") + "\n\n" + (
        notes or "<!-- manual-notes -->\n(none yet)\n")
    validate_report_provenance(report, metrics)
    report_path.write_text(report, encoding="utf-8")
    holdout = metrics["holdout"]
    adv = metrics["adversarial"]
    print(
        f"holdout: accuracy={_pct(holdout['accuracy'])} errors={len(holdout['errors'])} | "
        f"adversarial: accuracy={_pct(adv['accuracy'])} wrong_asserted={adv['wrong_category_asserted']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
