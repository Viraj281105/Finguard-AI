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
| **Server-side analytics layer (`com.finguard.backend.intelligence`)**: All metrics computed in `BigDecimal` scale 2 (`HALF_UP`) isolated by user. Browser arithmetic (`computeSummary`) removed (resolving KI-010). | Implemented & tested (`AnalyticsServiceTest`, `AnalyticsApiTest`) | `AnalyticsService`, `AnalyticsController` |
| **Exclusion of non-spending flows**: `TRANSFERS`, `CARD_PAYMENTS`, and `INVESTMENTS` are strictly excluded from income and expenses to avoid double-counting across internal transfers and bill payoffs (resolving KI-009). | Implemented & tested (`AnalyticsServiceTest`) | `AnalyticsService`, `contracts/categories.json` |
| **Refund netting**: Transactions categorized as `REFUNDS` offset gross expenses directly ($\text{netExpenses} = \max(0, \text{grossExpenses} - \text{refunds})$) and are never counted as earned income. | Implemented & tested (`AnalyticsServiceTest`) | `AnalyticsService` |
| **Cash flow and savings rate arithmetic**: $\text{netCashFlow} = \text{totalIncome} - \text{netExpenses}$. $\text{savingsRate} = ((\text{totalIncome} - \text{netExpenses}) / \text{totalIncome}) \times 100$, evaluated only when $\text{totalIncome} > 0$; otherwise returns `null` with explicit `savingsRateCaveat`. | Implemented & tested (`AnalyticsServiceTest`) | `AnalyticsService` |
| **Recurring commitments detection**: Recurring payments (subscriptions, bills) identified via merchant normalization, cadence clustering (monthly $\approx 30$ days, weekly $\approx 7$ days), and amount consistency ($\le 15\%$ variance). | Implemented & tested (`RecurringExpenseDetectorTest`) | `RecurringExpenseDetector` |
| **Explainable statistical outlier detection**: Unusually large expenses flagged if exceeding $2.5\times$ category median or $\text{mean} + 2\sigma$ with minimum ₹1,000 threshold. | Implemented & tested (`UnusualTransactionDetectorTest`) | `UnusualTransactionDetector` |
| **Server-side budgeting calculations (`com.finguard.backend.budgeting`)**: Monthly overall and category spending limits evaluated on calendar-month `txn_date`. Only active expense categories count as budgeted spending; credit card payments, internal transfers, and investments are excluded. Category refunds directly offset gross category spending. | Implemented & tested (`BudgetServiceTest`, `BudgetApiTest`) | `BudgetService`, `BudgetController` |
| **Concurrency-safe financial goals tracking**: Pessimistic row locking (`@Lock(PESSIMISTIC_WRITE)`) on the parent goal ensures concurrent contribution additions and deletions maintain consistent progress and status transitions. Contributions are explicitly designated as self-tracked allocations, not verified bank balances. | Implemented & tested (`GoalServiceTest`, `GoalApiTest`) | `GoalService`, `GoalController` |

## 2. Known gaps (do not build on these assumptions)

These are real limitations of the current code. Each is tracked in [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

- **No accounts and account-level balances.** The statement's closing balance is captured on the upload job, but individual account balances are not yet modeled.
- **Loans are snapshots.** No outstanding principal, remaining tenure or repayment history.
- **Investments are snapshots.** No contribution history or valuation history.
- **Single-currency (INR) assumption.** Multi-currency statements are not handled; all monetary values are treated as rupees (₹).

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

## 6. Budgeting and Financial Goals Rules (v0.7.0)

### 6.1 Budgeted Spending Definition
- **What counts as budgeted spending:**
  - Debits (`type = EXPENSE`) categorized into an active expense category from the taxonomy (e.g. `GROCERIES`, `DINING`, `HOUSING`, `UTILITIES`, etc.) are classified as budgeted spending.
  - Transactions in `UNCATEGORIZED` are classified as unbudgeted spending and reported explicitly in monthly budget overviews.
- **Refund treatment:**
  - Incoming credits (`type = INCOME`) categorized with an expense category (e.g., a merchant refund for `DINING`) or `REFUNDS` directly reduce gross category spending: $\text{actualSpending} = \max(0, \text{grossExpenses} - \text{refunds})$.
- **Excluded flows:**
  - `TRANSFERS`, `CARD_PAYMENTS`, and `INVESTMENTS` are non-spending flows and are **never** counted as consumer spending or budget consumption.
  - This prevents credit-card bill payoffs from double-counting against retail purchases already charged to cards.

### 6.2 Category and Overall Budget Interaction
- **Independent ceilings:**
  - An overall budget sets a high-level spending limit for the entire calendar month.
  - Category budgets set specific spending limits for individual categories.
  - The overall budget is **not** a naive sum of category budgets. Users may intentionally leave unallocated headroom or have unbudgeted categories.
  - Transactions are never double-counted: spending belongs to its individual category and simultaneously contributes to total monthly spending compared against the overall budget ceiling.
- **Performance thresholds:**
  - $\text{percentageUsed} < 80.0\%$: `UNDER_BUDGET` (normal healthy pace).
  - $80.0\% \le \text{percentageUsed} \le 100.0\%$: `APPROACHING_LIMIT` (warning state).
  - $\text{percentageUsed} > 100.0\%$: `OVER_BUDGET` (exceeded limit).
- **Month boundaries:**
  - Budgets are keyed by calendar month (`YYYY-MM`).
  - Budgets can be created for past, present, or future months.
  - Actual spending is calculated dynamically from transactions whose `txn_date` falls within `[YYYY-MM-01, end of month]`.

### 6.3 Financial Goals and Banking Disclaimer
- **Distinction from bank savings:**
  - Recorded goal contributions are self-tracked manual savings allocations for personal financial planning and pacing.
  - They do **not** represent verified bank account balances, automated fund reservations, or real bank transfers.
  - All responses include the explicit disclaimer:
    `"Recorded contributions are self-tracked allocations for planning purposes only, not verified bank deposits."`
- **Progress calculation:**
  - $\text{progressPercentage} = \min(100.0, (\text{currentAmount} / \text{targetAmount}) \times 100)$.
  - When $\text{currentAmount} \ge \text{targetAmount}$, goal status transitions automatically from `ACTIVE` to `COMPLETED`.
  - If a contribution is removed such that $\text{currentAmount} < \text{targetAmount}$, a `COMPLETED` goal automatically reverts to `ACTIVE`.
- **Target date pacing:**
  - If a goal has a `targetDate` in the future:
    $$\text{requiredMonthlyContribution} = \frac{\max(0, \text{targetAmount} - \text{currentAmount})}{\max(1, \text{monthsRemaining})}$$
  - If `targetDate` has passed and the goal is not completed, it is flagged with `isOverdue = true`.
- **Concurrency safety:**
  - Goal updates and contribution recordings employ pessimistic row-level locking (`SELECT ... FOR UPDATE`) in PostgreSQL, ensuring that concurrent contribution requests or deletes cannot result in race conditions, corrupted amounts, or inconsistent statuses.

## 7. Forecasting rules (v0.8.0)

Forecasts reuse the definitions above — nothing in this section redefines income, spending or savings.

### 7.1 What is forecast, from what

- **History** = the user's own transactions of the last 36 months, owner-scoped, read once per request and aggregated by
  `AnalyticsService` (the same code as the analytics screens): income, net spending (refunds offset it), net cash flow; transfers,
  card payments and investments excluded; uncategorised credits count as income, uncategorised debits as spending.
  A test asserts the forecast history equals the analytics monthly figures to the paisa.
- **Only complete calendar months train a forecast.** The month in progress is shown (month-to-date) and never used: a partial month
  would understate everything.
- **Missing is not zero.** A complete month between the first and the last with no income or spending is *unknown* (it may be an
  incomplete import); it is excluded and reported. A month containing only transfers, card payments or investments is likewise excluded.
  A month that *does* have income but no spending is real data and counts as zero spending. A late first month (first transaction
  after day 10) is flagged as possibly partial.
- **Projected months** are the months after the current one (`horizon` 1–12).

### 7.2 The method (`LEVEL_DAMPED_TREND` 2.0.0)

Deterministic and explainable; no machine learning, no randomness. For a forecast origin `o`, only observations at or before `o` are read.

1. Use the last 12 calendar months; fewer than 3 observations ⇒ no forecast.
2. With ≥ 5 observations, clip outliers to `median ± 3 × max(1.4826·MAD, 10 % of the median)`; clipped months are reported.
3. **Level** = mean of the last ≤ 6 (clipped) observations.
4. With ≥ 6 observations fit an ordinary-least-squares line on calendar months. The trend is applied **only if** its slope is
   significant (`|t| ≥ 2`); then `forecast(h) = fitted value at the last month + slope × Σ 0.8^k (k = 1…h)` (damped). Otherwise the forecast is the level.
5. Floor at zero.
6. **Recurring payments are a floor, not an addition.** Active detected recurring payments are already inside the observed spending,
   so they are not added again; if a projection would fall below their total it is raised to it (and says so).
7. **Seasonality is not modelled** (it needs ≥ 2 years). Annual and irregular payments are not detected (the recurring detector only
   emits monthly and weekly cadences), so a yearly bill can make its own month higher than projected.

### 7.3 Minimum evidence

| Forecast | Needs | Result otherwise |
|---|---|---|
| Cash flow / spending / savings | ≥ 3 usable complete months | `UNAVAILABLE`: empty projection, `null` totals |
| `RELIABLE` | ≥ 6 usable months, ≤ 1 gap in the last 6, latest complete month present, ≥ 3 transactions per month on average, regular income | `LIMITED` |
| Income and net | income in ≥ 3 usable months | income/net `null` (never 0); spending still projected |
| Regular income | all usable months have income and CV ≤ 0.5 | `LIMITED` + warning |
| A category | spending in ≥ 3 usable months; `RELIABLE` needs ≥ 6 and ≥ 75 % of months | listed in `withoutForecast` with the reason |
| A trend | ≥ 6 months and \|t\| ≥ 2 | no trend (`STABLE` / `NOT_ESTABLISHED`) |
| An uncertainty band | ≥ 8 past forecast errors of the same method at the same distance | no band, with a note |
| Budget run-rate | ≥ 7 recorded days and ≥ 5 transactions in scope (≥ 3 for a category) | no run-rate estimate |
| Budget history estimate | ≥ 3 complete months | no history estimate; with neither ⇒ `INDETERMINATE` |
| Backtest verdict | ≥ 6 scored forecasts at a horizon | `INCONCLUSIVE` |

`RELIABLE` describes the **evidence**, not the accuracy. Evaluation results are in [TESTING.md](TESTING.md#7-forecast-evaluation).

### 7.4 Uncertainty

There is no calibrated confidence. A band is `point + the 10th / 90th percentile of the method's own past errors` at that horizon on the
user's history (nearest-rank), clamped at zero for spending and income, and shown only with ≥ 8 past errors. On synthetic data the
share of next-month actuals falling inside such a band was 67–80 % (TESTING.md §7) — a description of past errors, not a guarantee.
Net bands use the past errors of the net forecast itself; scenario results and cumulative savings carry no band.

### 7.5 Budget outlook and scenarios

- Actual spend is the v0.7.0 budget definition. Only spend that has happened is called certain (`EXCEEDED`). The month-end outlook is a
  range from independent estimates (run-rate and history), never a single extrapolation; one estimate can raise at most `AT_RISK`.
  Recurring payments already made are removed from the daily rate and those still due are added once (no double counting). Data is
  considered recorded only through the latest transaction date; a stale import is flagged. Rules table: [API.md](API.md#get-apiforecastbudget-overruns).
- **Savings.** The projected surplus is hypothetical income minus spending. It is not a bank balance (FinGuard holds none), and goal
  contributions are self-tracked planning figures, never presented as deposits. Goals share one surplus, so the required pace of all
  dated goals is compared with it together.
- **Scenarios.** Flat monthly adjustments on top of the baseline: income % or ₹; category % or ₹; assumed recurring expenses;
  removing a detected recurring payment. A category cannot fall below zero; removing a recurring payment lowers its category rather than the total
  twice. No baseline ⇒ no scenario. Nothing is persisted.

---

## 8. Loan, one-time and saved-scenario rules (v0.9.0)

Loan arithmetic lives in `LoanAmortization` (BigDecimal, scale 2, HALF_UP) and is applied by `ScenarioSimulator`.

- **EMI** = P r (1+r)^n / ((1+r)^n - 1), r = annual rate / 1200 (reference: 1,00,000 at 12 % for 12 months = 8,884.88; 10,00,000 at 10 % for 120 months = 13,215.07).
- **Outstanding balance is an estimate.** The `loans` table stores the principal, EMI, rate and start date, not a statement. The balance is the principal amortised
  with the stored EMI for the whole months between the start date and the current month. A loan with no start date, an inactive loan or one that is already
  repaid under this rule cannot be simulated (400 with the reason). The annual rate is read as a percentage.
- **Adjustments** (at most one per loan): `CLOSE` pays the whole estimated balance at the start of projected month 1 and removes later instalments; `PREPAY` pays
  an extra amount at the start of month 1 and keeps the EMI (shorter tenure); `CHANGE_EMI` replaces the instalment. An EMI that does not exceed the first month of
  interest is rejected.
- **Deltas are differences of two deterministic runs** of the same loan (before and after), per month; they are added to the baseline spending for that month.
  The baseline forecast is not re-estimated, so an EMI already in the history is reduced by exactly the schedule difference. Per loan the response reports payoff
  months, remaining interest before and after, interest saved over the remaining life and the change in cash out inside the horizon. Closing a loan costs more cash
  inside a short horizon (the lump sum) even though it saves interest later; both are shown.
- **One-time items** (`INCOME`/`EXPENSE`, month 1..horizon, positive amount) land in exactly one projected month. A one-time income needs an income baseline.
- **Saved scenarios** store the adjustments, the result snapshot and the method version (`LEVEL_DAMPED_TREND 2.0.0`). They store no ledger data, so a re-run always uses
  today's records. **Reproducibility:** re-running while the records and the method are unchanged yields a byte-identical result (tested); if either changed, the response says which
  and returns the saved and the current figures side by side. The snapshot is never rewritten. A scenario whose baseline was unavailable cannot be saved.
- **Comparison** (UI) is subtraction of figures the backend calculated, over one horizon only; scenarios saved on different data show a warning that their baselines differ.
- **Not modelled:** prepayment charges, fees, floating-rate resets, day-count conventions, investment contributions (there is no investment cash-flow data to build a baseline from), and re-running a risk model (no risk model exists).
  Results are hypothetical estimates, never a lender statement, and are not stored.

---

## 9. Recommendation rules (v0.10.0)

Recommendations are **general guidance**, not financial, tax or investment advice. They are produced by `RecommendationEngine` (pure rules; no LLM, no external call) from figures the
analytics module already computes, for the last **3 complete months** (`from` = first day of the month three months back, `to` = last day of the previous month, using the injectable clock).
Every figure in an item's evidence can be reproduced from the named analytics endpoint with the same `from`/`to`. A rule whose input is missing is reported under `notEvaluated`, never guessed.
Comparisons are strict (a value exactly at a threshold does not fire), except RISING_SPEND, which fires **at or above** its minimum rise.

| Rule (`id`) | Fires when | Severity | Needs | Estimated monthly impact |
|---|---|---|---|---|
| `LOW_SAVINGS_RATE` | savings rate < **20 %** (net cash flow / income of the period) | `< 0` CRITICAL; `< 10` HIGH; otherwise MEDIUM | >= 3 months with records and income | (20 − rate) % of average monthly income |
| `HIGH_EMI_BURDEN` | sum of active-loan EMIs > **40 %** of average monthly income | > 60 % CRITICAL, otherwise HIGH | >= 3 months, income > 0, at least one active loan (silent without loans) | EMI − 40 % of income |
| `INVESTMENT_CONCENTRATION` | one investment type > **70 %** of portfolio value (current value, else amount invested) | MEDIUM | at least one investment (silent without) | none |
| `RISING_SPEND` | spending strictly rose in each of the last 3 consecutive months and by >= **10 %** first to last | MEDIUM | 3 consecutive months with records | last − first month |
| `UNCATEGORISED_SHARE` | uncategorised transactions > **20 %** of all transactions | INFO | >= 10 transactions | none |
| `SUBSCRIPTION_REVIEW` | >= **3** detected recurring payments in SUBSCRIPTIONS, or the same merchant twice | INFO | recurring detection | none |
| `EMERGENCY_FUND` | **never evaluated** | — | a verified savings balance, which FinGuard does not hold (goal contributions are self-tracked) | — |

- **Months with records** are months of the period that have at least one transaction; fewer than 3 means the period-based rules are listed as not evaluated and `healthy` is `false`.
- **Thresholds are configuration**: `recommendations.*` properties (environment variables such as `RECOMMENDATIONS_MIN_SAVINGS_RATE_PERCENT`); defaults are those above, plus `period-months = 3`,
  `min-months = 3`, `uncategorised-min-transactions = 10`, `high-savings-risk-below-percent = 10`, `critical-emi-share-percent = 60`, `preset-cut-percent = 10`.
- **Scenario preset**: for LOW_SAVINGS_RATE and RISING_SPEND, a 6-month scenario cutting the largest of DINING / SHOPPING / ENTERTAINMENT / SUBSCRIPTIONS by 10 % (if any had spending). It is only a starting
  point the user can change in the simulator.
- The heuristics are generic and do not know the user's circumstances; the UI says so and every item can be ignored. There is no dismissal state yet (KI-049).
