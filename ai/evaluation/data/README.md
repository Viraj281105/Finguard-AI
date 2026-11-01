# Evaluation data — SYNTHETIC

Every row in these files was **written by the developers of this repository (an AI assistant working with the
project owner), not exported from a bank**. Real, anonymised, labelled statements do not exist in this
repository yet (decision D-06 in `docs/DECISIONS.md`, `docs/KNOWN_ISSUES.md`). Consequences:

* The narrations imitate Indian bank formats (HDFC / ICICI / SBI / Axis / Kotak UPI, NEFT, IMPS, POS styles);
  real exports will contain spellings, truncations and merchants that are not represented here.
* The labels are the author's judgement. Where a real person would hesitate, the row is labelled
  `UNCATEGORIZED` (the classifier is expected to abstain).
* The same author wrote the rules and the data, so measured numbers are **optimistic** and say nothing
  about accuracy on real statements.

| File | Purpose |
|---|---|
| `manifest.json` | Cryptographic dataset registry (`dataset_id`, `file`, `data_provenance: "synthetic"`, canonical LF `sha256`, `row_count`, `label_procedure`, `frozen_holdout`). Verified on every `python -m evaluation.evaluate --check` run. |
| `synthetic_dev.csv` | The cases used *while building* the rules (copied from the unit tests). Scores on it are not a generalisation estimate. Disjoint from `synthetic_holdout.csv`. |
| `synthetic_holdout.csv` | Written after the first version of the rule table existed, from memory of bank-style narrations rather than from the rule list, and scored on first run before any rule was changed. Frozen against rule-tuning edits (`frozen_holdout: true`). `report.md` records any rule change made after looking at its errors; after such a change the file stops being blind. It is "held-out" only in this narrow sense. |
| `synthetic_adversarial.csv` | 28 synthetic adversarial and perturbation cases across 7 categories (`homoglyphs`, `truncation`, `mixed_case`, `reference_noise`, `merchant_lookalikes`, `direction_mismatch`, `prompt_injection`). |

Columns:
* `synthetic_dev.csv`, `synthetic_holdout.csv`: `description`, `type` (`INCOME` credit / `EXPENSE` debit), `expected` (taxonomy id).
* `synthetic_adversarial.csv`: `attack_category`, `description`, `type`, `expected`.

**Governance rule (`synthetic != real`):** Real bank statement exports must never be committed to this directory. Every committed dataset and generated evaluation report must carry `data_provenance: "synthetic"` (`synthetic ≠ real`).
