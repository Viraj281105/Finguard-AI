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
| `synthetic_dev.csv` | The cases used *while building* the rules (copied from the unit tests). Scores on it are not a generalisation estimate. |
| `synthetic_holdout.csv` | Written after the first version of the rule table existed, from memory of bank-style narrations rather than from the rule list, and scored on first run before any rule was changed. `report.md` records any rule change made after looking at its errors; after such a change the file stops being blind. It is "held-out" only in this narrow sense. |

Columns: `description`, `type` (`INCOME` credit / `EXPENSE` debit), `expected` (taxonomy id).
