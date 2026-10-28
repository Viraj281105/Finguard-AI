# Classifier evaluation report

> **Read this first.** The data is **synthetic**: written by the project's developers, not taken from
> real bank statements. The same author wrote the rules and the data, so these figures are
> **optimistic** and are **not** an estimate of accuracy on real statements. The classifier is a
> deterministic rule baseline; **no machine-learning model was trained**, so there is no model metric.
> `confidence` is a rule-strength tier, not a calibrated probability.

Classifier under test: `rules-1.0.0` (method: rules). Regenerate with `cd ai; .\.venv\Scripts\python -m evaluation.evaluate`.

## Summary

| Metric | `dev` | `holdout` |
|---|---:|---:|
| Rows | 63 | 134 |
| Accuracy (an abstention is correct when the label is UNCATEGORIZED) | 100.0% | 100.0% |
| Coverage: labelled rows that received a category | 100.0% | 100.0% |
| Precision when a category was asserted | 100.0% | 100.0% |
| Recall on labelled rows | 100.0% | 100.0% |
| Abstained where the label was UNCATEGORIZED | 100.0% | 100.0% |
| Macro-F1 over labelled categories | 100.0% | 100.0% |
| Wrong category asserted (count) | 0 | 0 |
| Rows asserted and flagged for review (precision) | 5 (100.0%) | 8 (100.0%) |
| Rows asserted and not flagged for review (precision) | 46 (100.0%) | 109 (100.0%) |

Latency (in-process, 200 items, median of 20 runs): **26.47 ms** (max 31.14 ms). Measured on the machine that generated this report; it excludes HTTP and JSON overhead.

## `dev` — per-category results

| Category | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| CARD_PAYMENTS | 3 | 100.0% | 100.0% | 100.0% |
| DINING | 3 | 100.0% | 100.0% | 100.0% |
| EDUCATION | 2 | 100.0% | 100.0% | 100.0% |
| ENTERTAINMENT | 2 | 100.0% | 100.0% | 100.0% |
| FEES | 3 | 100.0% | 100.0% | 100.0% |
| GROCERIES | 4 | 100.0% | 100.0% | 100.0% |
| HEALTHCARE | 3 | 100.0% | 100.0% | 100.0% |
| HOUSING | 2 | 100.0% | 100.0% | 100.0% |
| INCOME | 3 | 100.0% | 100.0% | 100.0% |
| INSURANCE | 2 | 100.0% | 100.0% | 100.0% |
| INVESTMENTS | 4 | 100.0% | 100.0% | 100.0% |
| LOAN_PAYMENTS | 2 | 100.0% | 100.0% | 100.0% |
| REFUNDS | 3 | 100.0% | 100.0% | 100.0% |
| SHOPPING | 4 | 100.0% | 100.0% | 100.0% |
| SUBSCRIPTIONS | 3 | 100.0% | 100.0% | 100.0% |
| TRANSFERS | 2 | 100.0% | 100.0% | 100.0% |
| TRANSPORT | 3 | 100.0% | 100.0% | 100.0% |
| UTILITIES | 3 | 100.0% | 100.0% | 100.0% |

### `dev` — errors (0)

None.

### `dev` — confusion matrix (rows: expected, columns: predicted)

| expected \ predicted | CARD_PAYMENTS | DINING | EDUCATION | ENTERTAINMENT | FEES | GROCERIES | HEALTHCARE | HOUSING | INCOME | INSURANCE | INVESTMENTS | LOAN_PAYMENTS | REFUNDS | SHOPPING | SUBSCRIPTIONS | TRANSFERS | TRANSPORT | UNCATEGORIZED | UTILITIES |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CARD_PAYMENTS | 3 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| DINING |  | 3 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| EDUCATION |  |  | 2 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| ENTERTAINMENT |  |  |  | 2 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| FEES |  |  |  |  | 3 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GROCERIES |  |  |  |  |  | 4 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| HEALTHCARE |  |  |  |  |  |  | 3 |  |  |  |  |  |  |  |  |  |  |  |  |
| HOUSING |  |  |  |  |  |  |  | 2 |  |  |  |  |  |  |  |  |  |  |  |
| INCOME |  |  |  |  |  |  |  |  | 3 |  |  |  |  |  |  |  |  |  |  |
| INSURANCE |  |  |  |  |  |  |  |  |  | 2 |  |  |  |  |  |  |  |  |  |
| INVESTMENTS |  |  |  |  |  |  |  |  |  |  | 4 |  |  |  |  |  |  |  |  |
| LOAN_PAYMENTS |  |  |  |  |  |  |  |  |  |  |  | 2 |  |  |  |  |  |  |  |
| REFUNDS |  |  |  |  |  |  |  |  |  |  |  |  | 3 |  |  |  |  |  |  |
| SHOPPING |  |  |  |  |  |  |  |  |  |  |  |  |  | 4 |  |  |  |  |  |
| SUBSCRIPTIONS |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 3 |  |  |  |  |
| TRANSFERS |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 2 |  |  |  |
| TRANSPORT |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 3 |  |  |
| UNCATEGORIZED |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 12 |  |
| UTILITIES |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 3 |

## `holdout` — per-category results

| Category | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| CARD_PAYMENTS | 4 | 100.0% | 100.0% | 100.0% |
| DINING | 7 | 100.0% | 100.0% | 100.0% |
| EDUCATION | 6 | 100.0% | 100.0% | 100.0% |
| ENTERTAINMENT | 5 | 100.0% | 100.0% | 100.0% |
| FEES | 7 | 100.0% | 100.0% | 100.0% |
| GROCERIES | 8 | 100.0% | 100.0% | 100.0% |
| HEALTHCARE | 7 | 100.0% | 100.0% | 100.0% |
| HOUSING | 4 | 100.0% | 100.0% | 100.0% |
| INCOME | 8 | 100.0% | 100.0% | 100.0% |
| INSURANCE | 5 | 100.0% | 100.0% | 100.0% |
| INVESTMENTS | 9 | 100.0% | 100.0% | 100.0% |
| LOAN_PAYMENTS | 6 | 100.0% | 100.0% | 100.0% |
| REFUNDS | 5 | 100.0% | 100.0% | 100.0% |
| SHOPPING | 8 | 100.0% | 100.0% | 100.0% |
| SUBSCRIPTIONS | 7 | 100.0% | 100.0% | 100.0% |
| TRANSFERS | 3 | 100.0% | 100.0% | 100.0% |
| TRANSPORT | 10 | 100.0% | 100.0% | 100.0% |
| UTILITIES | 8 | 100.0% | 100.0% | 100.0% |

### `holdout` — errors (0)

None.

### `holdout` — confusion matrix (rows: expected, columns: predicted)

| expected \ predicted | CARD_PAYMENTS | DINING | EDUCATION | ENTERTAINMENT | FEES | GROCERIES | HEALTHCARE | HOUSING | INCOME | INSURANCE | INVESTMENTS | LOAN_PAYMENTS | REFUNDS | SHOPPING | SUBSCRIPTIONS | TRANSFERS | TRANSPORT | UNCATEGORIZED | UTILITIES |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CARD_PAYMENTS | 4 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| DINING |  | 7 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| EDUCATION |  |  | 6 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| ENTERTAINMENT |  |  |  | 5 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| FEES |  |  |  |  | 7 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GROCERIES |  |  |  |  |  | 8 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| HEALTHCARE |  |  |  |  |  |  | 7 |  |  |  |  |  |  |  |  |  |  |  |  |
| HOUSING |  |  |  |  |  |  |  | 4 |  |  |  |  |  |  |  |  |  |  |  |
| INCOME |  |  |  |  |  |  |  |  | 8 |  |  |  |  |  |  |  |  |  |  |
| INSURANCE |  |  |  |  |  |  |  |  |  | 5 |  |  |  |  |  |  |  |  |  |
| INVESTMENTS |  |  |  |  |  |  |  |  |  |  | 9 |  |  |  |  |  |  |  |  |
| LOAN_PAYMENTS |  |  |  |  |  |  |  |  |  |  |  | 6 |  |  |  |  |  |  |  |
| REFUNDS |  |  |  |  |  |  |  |  |  |  |  |  | 5 |  |  |  |  |  |  |
| SHOPPING |  |  |  |  |  |  |  |  |  |  |  |  |  | 8 |  |  |  |  |  |
| SUBSCRIPTIONS |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 7 |  |  |  |  |
| TRANSFERS |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 3 |  |  |  |
| TRANSPORT |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 10 |  |  |
| UNCATEGORIZED |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 17 |  |
| UTILITIES |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | 8 |

## Changes made after reading the held-out errors

See the section at the end of this file, maintained by hand when rules change.

<!-- manual-notes -->
### First-look result on the held-out file (before any rule change)

Scored once on `rules-1.0.0` as first written, on all 133 rows: accuracy **92.5 %**, coverage of labelled rows
**92.5 %**, precision when a category was asserted **99.1 %**, macro-F1 **95.4 %**, **1** wrong category asserted,
**9** rows abstained that had a label, **10** errors in total. This is the only figure in this repository that is
not the result of tuning, and it is still **synthetic and optimistic** (same author for rules and data).

### What was changed afterwards, and why the table above reads 100 %

After reading those 10 errors the rule table was edited in ways that are general rather than row-specific
(still, they were motivated by these rows): removed the generic `renewal` subscription keyword (it labelled
`BMTC BUS PASS RENEWAL` as a subscription - the one wrong assertion); moved `fastag` above the generic
`recharge` utility rule; added the brands `hypercity`, `chai point`, `haldirams`, `byjus`, `medibuddy`,
`amazon fresh/grocery`, `ice creams` and the phrases `credit interest` / `interest capitalised`.
Consequently the 100 % shown for `holdout` in the tables above is **not** a generalisation estimate: the file
is no longer blind. Use the first-look figures, and treat even those as an upper bound for real statements.

### What this report does not tell you

* Nothing about real bank statements (none were available).
* Nothing about calibration: `confidence` is a rule-strength tier. 0.90 does not mean "90 % likely correct".
* The abstentions on `UPI-<person name>`, ATM withdrawals and payment gateways are deliberate; real statements
  contain many of them, so real-world coverage will be much lower than the synthetic coverage above.
