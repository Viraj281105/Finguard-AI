# Financial data rules

How FinGuard AI represents money, dates and direction, what it deliberately does **not** model yet, and the
rules every future calculation must follow. Status labels: see [README.md](README.md#status-vocabulary-used-in-every-document).

## 1. Rules that hold today

| Rule | Status | Where enforced |
|---|---|---|
| Rule | Status | Where enforced |
|---|---|---|
| Money is a `BigDecimal` in Java and `numeric(38,2)` in PostgreSQL. No floating point in the backend. | Implemented & tested (`LedgerApiTest` round-trips 12345.67; `StatementParserRegistryTest` checks scale) | entities in `ledger/domain`, migrations `V1`–`V4` |
| A transaction `amount` is always **positive**; the direction lives in `type` (`INCOME` / `EXPENSE`). | Implemented & tested | `TransactionRequest` (`@Positive`), parsers |
| A transaction has a dedicated calendar `LocalDate` (`txn_date`). No time zone is attached to financial dates. | Implemented & tested (`LedgerApiTest`, `ImportApiTest`) | `Transaction.date`, `V3__dated_idempotent_ledger.sql` |
| Repeated statement uploads are idempotent: duplicate transactions are detected and skipped via user-scoped SHA-256 fingerprinting. | Implemented & tested (`ImportApiTest`, `TransactionFingerprintTest`) | `TransactionFingerprint`, `ImportWorker`, `uq_transactions_user_fingerprint` |
| An investment `amountInvested` is positive; `currentValue` is optional and non-negative. | Implemented & tested | `InvestmentRequest` |
| A loan's `principal` and `emiAmount` are positive; `interestRate` is zero or positive with up to 4 decimal places (`numeric(10,4)`, e.g. `8.7550`); `active` defaults to `true`. | Implemented & tested (`LegacyDatabaseAdoptionTest`) | `LoanRequest`, `Loan.interestRate`, `V3__dated_idempotent_ledger.sql` |
| Every record belongs to exactly one user; another user's record is reported as **not found**, never as forbidden. | Implemented & tested (`OwnershipApiTest`, with a mutation check) | repositories `findByIdAndUserId`, services |
| Imported statement rows use header-based dynamic mapping (`HeaderDrivenStatementParser`). A positive debit becomes an `EXPENSE`; otherwise a positive credit becomes `INCOME`. | Implemented & tested on synthetic fixtures & permutations (resolving KI-003, KI-005) | `parser/HeaderDrivenStatementParser`, `StatementParserRegistryTest` |
| Amount parsing handles Indian number formats (`1,23,456.78`), currency symbols (`₹`, `Rs`), parenthesized negatives (`(1,234.50)`), and trailing/leading `Dr`/`Cr` suffixes. | Implemented & tested (resolving KI-006) | `parser/CsvSupport.parseAmount`, `StatementParserRegistryTest` |
| Bank dates are parsed with preferred bank patterns and multi-pattern fallback formatters (`dd/MM/yy`, `dd/MM/yyyy`, `dd MMM yyyy`, `dd-MM-yyyy`, `yyyy-MM-dd`). | Implemented & tested | `parser/CsvSupport.parseDate` |
| Statement row reconciliation invariant: $\text{totalRows} = \text{rowsProcessed} + \text{duplicateRows} + \text{rejectedRows} + \text{skippedRows}$. Malformed rows are recorded with 1-indexed row numbers, error reasons, and PII-sanitized snippets (long digits masked). | Implemented & tested (resolving KI-004) | `ImportWorker`, `UploadStatusResponse`, `V4__resilient_ingestion_reports.sql` |
| A transaction category is one id of a fixed, versioned taxonomy (§5), enforced by a database `CHECK`; a category exists exactly when a source says who decided it (`NONE`/`RULE`/`MODEL`/`USER`). | Implemented & tested (`CategoryApiTest`, `TaxonomyContractTest`, `LegacyDatabaseAdoptionTest`) | `TransactionCategory`, `V5__transaction_categorisation.sql` |
| A category chosen by the user is never overwritten by automation, including during import, re-upload and re-classification. | Implemented & tested (`ImportClassificationTest`, `CategoryApiTest`) | `TransactionService.applyAutomaticCategories` (row lock + `category_source = NONE`) |
| Classification never changes `amount`, `type` or `date`. | Implemented & tested (`ImportClassificationTest.transfersRefunds…`) | automatic categories are applied only to category columns |
| Interrupted statement import jobs automatically transition to `FAILED` with safe re-upload instructions on startup. | Implemented & tested (resolving KI-007) | `ImportRecoveryListener`, `ImportRecoveryTest` |

## 2. Known gaps (do not build on these assumptions)

These are real limitations of the current code. Each is tracked in [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

- **Transfers, refunds, reversals and credit-card bill payments are *labelled* (§5) but not yet *used*.** They are
  stored as ordinary `INCOME`/`EXPENSE` rows with a non-spending or refund category; an own-account transfer is still
  one `EXPENSE` plus one `INCOME` that are not linked to each other, and a refund is not netted against the expense it
  reverses. v0.6.0 builds the analytics rules on these labels. (KI-009)
- **No accounts and account-level balances.** The statement's closing balance is captured on the upload job, but individual account balances are not yet modeled. (KI-009)
- **Loans are snapshots.** No outstanding principal, remaining tenure or repayment history.
- **Investments are snapshots.** No contribution history or valuation history.
- **Dashboard totals are computed in the browser** with JavaScript floating point, over all time, with no month
  boundaries. (KI-010) The backend returns correct decimals; only the dashboard arithmetic is affected. Since 0.5.0
  those client-side totals leave out `NON_SPENDING` and `REFUND` categories (§5) — a stop-gap, not the analytics rules.

## 3. Conventions for financial code

1. **Money:** `BigDecimal` end to end; scale 2 for rupee amounts; rounding `HALF_UP` applied once, at the
   boundary of a calculation, never inside intermediate steps. JSON carries amounts as numbers with at most two
   decimals; the frontend must not do arithmetic on them (it formats only).
2. **Dates:** a transaction has a calendar `LocalDate` (`txn_date`). No time zone is attached to a bank date.
   Months are calendar months of that date. Timestamps like `created_at` are UTC and are never used for
   business grouping.
3. **Direction:** keep `amount > 0` with a direction. Derived values (net, savings) are computed, not stored.
4. **Transaction Fingerprinting Specification (v0.3.0):**
   - Canonical string format: `userId|YYYY-MM-DD|type|positiveScaledAmount|normalizedTitle|occurrenceIndex`
   - Hashing: SHA-256 output as a 64-character lowercase hexadecimal digest.
   - User isolation: Includes authenticated `userId` so different users importing identical bank statements never collide.
   - Scale normalization: Amount is stripped of trailing zeros (`amount.stripTrailingZeros().toPlainString()`), ensuring `20.0` and `20.00` yield the same hash.
   - Title normalization: Lowercased, leading/trailing whitespace removed, multiple internal spaces collapsed.
   - Intra-file occurrence index: A 0-based counter tracks repeated identical transactions within the same import (e.g. buying two ₹20 coffees on the same day). Both coffee transactions import successfully, but re-uploading the same statement identifies both as duplicates.
   - Concurrency safety: PostgreSQL partial unique index `uq_transactions_user_fingerprint ON transactions (user_id, fingerprint) WHERE fingerprint IS NOT NULL` prevents concurrent upload races.
5. **Exclusions must be explicit.** Any metric states which flows it excludes (transfers, refunds, one-offs) and
   returns that statement with the result so a reader can reproduce it.
5. **Insufficient data is a result, not an error and not a guess.** A calculation returns `insufficient`
   with the reason (for example "needs 3 months, found 1") rather than a number built on too little history.
6. **Determinism:** the same persisted data and the same model version produce the same output. Computed
   results that are stored keep their inputs and model version so they can be reproduced.
7. **AI never overrides arithmetic.** Models may *suggest* a category or a forecast; totals, scores and
   scenario deltas are computed deterministically from the stored data. A user's manual category always wins
   over a model's.
8. **Ownership:** every query that touches user data is scoped by the authenticated user's id, taken from the
   security context — never from a request parameter or body.

## 4. Draft risk-score specification — **Planned, not implemented**

The only existing description of the risk score is in the original project plan. It is summarised here so the
v0.7.0 work starts from it; it must be turned into a precise, tested specification (`docs/RISK_MODEL.md`) before
any code is written. There is no risk-score code in the repository today.

| Item | Original design | Open points that v0.7.0 must settle |
|---|---|---|
| Direction | Higher score = higher risk; 0–100 | — |
| Bands | 0–25 low, 26–50 medium, 51–75 high, 76–100 critical | — |
| Weights | Liquidity 0.25, Debt burden 0.25, Spending volatility 0.20, Savings stability 0.20, Investment diversification 0.10 (sum 1.00) | Keep unless the owner changes them |
| Liquidity | survival months = liquid assets ÷ average monthly expenses | What counts as *liquid assets* (no balances are stored today); how to treat zero expenses |
| Debt burden | total EMI ÷ monthly income | Zero or irregular income; EMI taken from loans **or** from transactions, not both |
| Volatility | coefficient of variation of monthly spend over 6 months | Exclude transfers and one-offs; fewer than 6 months of data |
| Savings stability | slope of a linear regression on monthly savings | Sign convention; minimum months; scaling |
| Diversification | Herfindahl–Hirschman index of the investment allocation | No investments; single holding |
| Normalisation | "normalise each dimension to 0–100" | **Not defined.** Breakpoints must be chosen, justified and tested |
| Missing data | not specified | Omit and re-weight, or refuse to score; always report a confidence/coverage level |

Acceptance of the specification is part of [v0.7.0](RELEASE_PLAN.md#v070--explainable-risk-score).

## 5. Transaction categories

*(Implemented in v0.5.0. Identifiers: [`contracts/categories.json`](../contracts/categories.json), the single source of
truth, verified against Python, Java, the SQL `CHECK` constraint and TypeScript by one test each.)*

### 5.1 Taxonomy and flow

Every category has a **flow** that says how later arithmetic must treat it, and the **directions** it may be
assigned to automatically.

| Id | Label | Flow | Direction |
|---|---|---|---|
| `INCOME` | Income | income | credit |
| `GROCERIES` `DINING` `TRANSPORT` `HOUSING` `UTILITIES` `HEALTHCARE` `INSURANCE` `SHOPPING` `SUBSCRIPTIONS` `EDUCATION` `ENTERTAINMENT` `FEES` `LOAN_PAYMENTS` | … | expense | debit |
| `INVESTMENTS` | Investments | **non-spending** | credit or debit |
| `TRANSFERS` | Transfers | **non-spending** | credit or debit |
| `CARD_PAYMENTS` | Credit card payments | **non-spending** | debit |
| `REFUNDS` | Refunds & reversals | **refund** | credit |
| `UNCATEGORIZED` | Uncategorized | unknown | credit or debit |

Why these flows:

- **Transfers** between the user's own accounts are not income or spending. **Credit-card bill payments** settle
  spending that is counted when the card was charged; counting the payment as well would double count it.
  **Investment movements** convert cash into assets (and back); they are not consumption. All three are `NON_SPENDING`
  and are excluded from income and expense totals.
- **Refunds and reversals** are money coming back from a merchant. They are not income; they reduce the expense they
  reverse. Until v0.6.0 defines the netting (and links a refund to its expense), they are only excluded from income.
- **Loan payments** are an expense (debt service). v0.6.0 may split principal and interest.
- **Insurance** is its own category rather than being folded into healthcare or investments.
- **There is no "cash" category.** ATM withdrawals are left uncategorised on purpose (§5.3).

Legacy free-text categories were normalised by migration V5 (`Food` → `DINING` is the one lossy-looking mapping:
`Food` could have meant groceries; users can correct it) and are still accepted as input labels.

### 5.2 Who decides: provenance and precedence

`category_source`: `NONE` (no category) · `RULE` (rule baseline) · `MODEL` (trained model; none exists) · `USER`.

1. **`USER` always wins.** A user category is never replaced by import, re-import, re-classification or a model.
   A user may also choose `UNCATEGORIZED` deliberately ("I looked; it stays uncategorised") — that, too, is a
   `USER` decision and is not retried.
2. Automation may only fill a transaction that is still `NONE`, and only with a category that is in the taxonomy,
   is not `UNCATEGORIZED`, and suits the direction (the backend re-checks; it does not trust the AI service).
3. The classifier **abstains** instead of guessing. An abstention leaves the row `NONE`, so it appears in the
   "needs a category" list and is retried by "Categorize automatically".
4. Re-uploading a statement never re-classifies: duplicates are skipped by fingerprint before classification.

### 5.3 What the rule baseline does and does not claim

- It is a hand-written rule set (`rules-1.0.0`) for Indian bank narrations, **not a trained model**.
- `confidence` is a rule-strength tier (weak 0.55 / medium 0.75 / strong 0.90). It is **not calibrated**, so the UI
  shows no percentage; it shows `Auto`, and `Review` when `needs_review` is set (weak rule, or rules for another
  category also matched).
- Deliberate abstentions: payments to people (`UPI-<name>`), ATM/cash, wallets and gateways (`BillDesk`, `Paytm`,
  `Amazon Pay`), ties between categories, and a credit that names an expense merchant (probably a refund — not
  asserted; `REFUNDS` is offered as an alternative).
- Business rules at highest priority: credit-card bill payment phrases → `CARD_PAYMENTS`; refund / reversal /
  cashback → `REFUNDS` (credits only); own-account transfer phrases → `TRANSFERS`; a bare `NEFT`/`IMPS`/`RTGS` is only
  a weak `TRANSFERS` hint, and never counts as competing evidence for another category.
- Measured quality: only on synthetic data written by the same author as the rules —
  [`ai/evaluation/report.md`](../ai/evaluation/report.md). No figure about real statements exists.
