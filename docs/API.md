# HTTP API

Base URL (development): `http://localhost:8080`. All paths are under `/api`. JSON in, JSON out, except the file
upload (`multipart/form-data`).

**Status:** every endpoint listed here exists and is exercised by an automated test unless a row says otherwise
(tests: `AuthApiTest`, `OwnershipApiTest`, `LedgerApiTest`, `CategoryApiTest`, `ImportApiTest`,
`ImportClassificationTest`, `AnalyticsApiTest`, `BudgetApiTest`, `GoalApiTest`, `ForecastApiTest`). Nothing outside this list exists (see [ROADMAP.md](ROADMAP.md)).

There is no API version prefix. See [DECISIONS.md](DECISIONS.md) D-07.

## Authentication

- `POST /api/auth/**` is public. **Every other endpoint requires** `Authorization: Bearer <access token>`.
- Tokens are signed JWTs (HMAC). Claims: `sub` (email), `typ` (`access` or `refresh`), `jti`, `iat`, `exp`.
- Lifetimes: access **15 minutes** (900,000 ms), refresh **7 days** (604,800,000 ms) (configurable: `JWT_EXPIRATION_MS`, `JWT_REFRESH_EXPIRATION_MS`).
- A refresh token is rejected as a bearer token; an access token is rejected by `/auth/refresh`.
- The user is always identified from the token. No endpoint accepts a user id.
- **Refresh Token Rotation & Revocation**: Refresh tokens are single-use. When `/api/auth/refresh` is called, a new refresh token is issued and the previous token is marked revoked and replaced. If an already-replaced refresh token is re-submitted (token reuse attack), all active sessions for that user account are revoked immediately (D-03b).
- **Cookies**: Auth endpoints set an `HttpOnly`, `SameSite=Strict` cookie for `refreshToken` scoped to `Path=/api/auth`. In alignment with secure cookie architecture (R-4), refresh tokens are omitted from JSON response bodies and managed exclusively via the `HttpOnly` cookie.
- **Brute-Force Rate Limiting & Denial of Service Protection**: An in-memory bounded LRU map (5,000 max entries) tracks failed authentication attempts. Limits: 5 failed attempts per (IP, normalized email) pair in 60s, 20 failed attempts per IP, and 15 failed attempts per account (preventing lock-out DoS attacks from rotated IPs). Excess requests return HTTP `429 Too Many Requests` with a `Retry-After: 60` header.
- **Trusted Proxy Resolution**: Client IP addresses are resolved from `X-Forwarded-For` only when requests originate from configured trusted proxies (`app.trusted-proxies`); untrusted client-supplied forwarding headers are discarded (R-3).
- **Email Normalization**: Emails are trimmed and converted to lowercase on registration, login, and token generation (D-05).

### `POST /api/auth/register`

```json
{ "name": "Asha", "email": "asha@example.com", "password": "securepassword123" }
```
`name` and `email` required; `email` must be valid (normalized to lowercase); `password` at least 10 characters (D-02).

`200` →
```json
{ "accessToken": "eyJ…", "email": "asha@example.com", "name": "Asha" }
```
Sets `Set-Cookie: refreshToken=...; HttpOnly; SameSite=Strict; Path=/api/auth; Max-Age=604800`.
`409` `Email already registered` · `400` validation (see Errors).

### `POST /api/auth/login`

```json
{ "email": "asha@example.com", "password": "securepassword123" }
```
`200` same body as register (`accessToken`, `email`, `name`) and sets the `refreshToken` cookie · `401` `Invalid email or password` · `429` `Too many login attempts. Please try again later.` with `Retry-After: 60`.

### `POST /api/auth/refresh`

```json
{}
```
*(Refresh token read from `refreshToken` HttpOnly cookie)*

`200` new `accessToken` (omits refreshToken from body) and updated `Set-Cookie` · `401` `Invalid refresh token` or `Refresh token expired or revoked`.
Replay of an already-rotated token within the 30s grace window returns 401 without terminating concurrent tab sessions; replay after 30s triggers token reuse detection and revokes all refresh tokens for that user account.

### `POST /api/auth/logout`

```json
{ "allDevices": false }
```
*(Optional body: `allDevices` defaults to `false`)*

`200` →
```json
{ "status": 200, "message": "Logged out successfully" }
```
Clears the `refreshToken` cookie with `Max-Age=0`. When `allDevices: true`, revokes all active session refresh tokens for the authenticated user; otherwise revokes only the current session's token.

## Transactions — `/api/transactions`

Request (create and replace):

| Field | Type | Rules |
|---|---|---|
| `title` | string | required, not blank |
| `amount` | number | required, `> 0` |
| `type` | `"INCOME"` \| `"EXPENSE"` | required |
| `date` | string (`yyyy-MM-dd`) | optional, defaults to current date (`LocalDate.now()`) |
| `category` | string | optional. A category id from the [taxonomy](#category-taxonomy) (any letter case) or a legacy label such as `"Food"` (normalised, see below). Anything else is `400`. A category supplied here is recorded as the **user's** choice (`categorySource: "USER"`). On `PUT`: absent keeps the current category, `""` clears it. |
| `note` | string | optional |

Response object:
```json
{
  "id": "6c1f…",
  "title": "UPI-SWIGGY-swiggy@icici",
  "amount": 450.00,
  "type": "EXPENSE",
  "category": "DINING",
  "note": "Imported from HDFC | 2026-03-01",
  "date": "2026-03-01",
  "source": "IMPORT",
  "categorySource": "RULE",
  "categoryConfidence": 0.7500,
  "categoryConfidenceCalibrated": false,
  "categoryNeedsReview": false,
  "categoryModelVersion": "rules-1.0.0",
  "categoryRule": "dining.swiggy",
  "createdAt": "2026-03-05T10:15:30.123456"
}
```
`date` is the financial transaction date (parsed bank date, or given at manual creation).
`source` is `"MANUAL"` or `"IMPORT"`.

**Category provenance** (added in 0.5.0, all additive):

| Field | Meaning |
|---|---|
| `category` | A taxonomy id, or `null` when the transaction has no category. |
| `categorySource` | `"NONE"` no category · `"RULE"` assigned by the AI service's rule baseline · `"MODEL"` assigned by a trained model (none exists yet) · `"USER"` chosen or confirmed by the user. **`USER` is never overwritten by automation.** |
| `categoryConfidence` | The classifier's raw score for an automatic category; `null` for `USER`/`NONE`. For `RULE` it is a **rule-strength tier** (0.55 weak / 0.75 medium / 0.90 strong), **not a probability**. |
| `categoryConfidenceCalibrated` | `true` only when `categoryConfidence` is a calibrated probability. Always `false` today; clients must not show a percentage unless it is `true`. |
| `categoryNeedsReview` | Automatic category on weak or conflicting evidence. |
| `categoryModelVersion` / `categoryRule` | Version of the rule set or model, and the id of the rule that matched. |

### Category taxonomy

Identifiers are the same in the AI service, Java, the database `CHECK` constraint and the frontend; the single
source of truth is [`contracts/categories.json`](../contracts/categories.json), verified by a test in each layer.

| Flow | Categories |
|---|---|
| `INCOME` | `INCOME` |
| `EXPENSE` | `GROCERIES` `DINING` `TRANSPORT` `HOUSING` `UTILITIES` `HEALTHCARE` `INSURANCE` `SHOPPING` `SUBSCRIPTIONS` `EDUCATION` `ENTERTAINMENT` `FEES` `LOAN_PAYMENTS` |
| `NON_SPENDING` (money moved or settled, never an ordinary expense) | `INVESTMENTS` `TRANSFERS` `CARD_PAYMENTS` |
| `REFUND` | `REFUNDS` |
| `UNKNOWN` | `UNCATEGORIZED` |

Rules and rationale: [FINANCIAL_RULES.md](FINANCIAL_RULES.md#5-transaction-categories). Legacy labels accepted on input and mapped
the same way the V5 migration mapped stored data: `Salary`/`Freelance`/`Business`/`Investment Returns` → `INCOME`,
`Food` → `DINING`, `Rent` → `HOUSING`, `EMI` → `LOAN_PAYMENTS`, `Other` → `UNCATEGORIZED` (others by name).

| Method & path | Result |
|---|---|
| `POST /api/transactions` | `201` created object |
| `GET /api/transactions` | `200` JSON array of the caller's transactions, sorted deterministically by `date DESC, createdAt DESC`. Supports query parameters and pagination headers. |
| `GET /api/transactions/{id}` | `200` object · `404` if missing **or owned by someone else** |
| `PUT /api/transactions/{id}` | `200` updated object (full replacement of the fields, preserves existing `date` if omitted; see `category` rules above — **0.5.0 change:** an absent `category` now keeps the current one instead of clearing it) · `400` unknown category · `404` |
| `PATCH /api/transactions/{id}/category` | Records the user's own category. Body `{"category": "GROCERIES"}` (id or legacy label; blank/missing/unknown → `400`). `200` updated object with `categorySource: "USER"`, confidence/rule/version cleared · `404` missing or not owned |
| `POST /api/transactions/reclassify` | Asks the AI service again for the caller's transactions with no category (`categorySource = NONE`), newest first. Query `limit` (default 200, max 500). Never touches a `USER` category and only the caller's rows. `200` `{ "status", "attempted", "categorized", "needsReview", "stillUncategorized" }`; `status` is `COMPLETED`, `PARTIAL`, `UNAVAILABLE` (AI service down — nothing changed, still `200`) or `NOT_ATTEMPTED` (nothing to do) |
| `DELETE /api/transactions/{id}` | `204` · `404` |

### Query Parameters for `GET /api/transactions`

| Parameter | Type | Description |
|---|---|---|
| `page` | integer | Page index (0-based, default 0). Must be `>= 0`. |
| `size` | integer | Page size (1 to 100, default 20). |
| `unpaged` | boolean | Set to `true` to return all matching rows unpaged (default `false`). |
| `from` | `yyyy-MM-dd` | Inclusive start date for `date`. |
| `to` | `yyyy-MM-dd` | Inclusive end date for `date`. |
| `type` | string | Filter by `"INCOME"` or `"EXPENSE"`. |
| `category` | string | Filter by category id (or legacy label). `UNCATEGORIZED` matches transactions with no category **and** those the user explicitly left uncategorised. Unknown values → `400`. |
| `needsReview` | boolean | `true` lists only automatic categories flagged for review (the review queue). |

### Pagination Response Headers

To preserve backward compatibility with clients expecting a top-level JSON array (`List<TransactionResponse>`), pagination metadata is conveyed through HTTP response headers:

- `X-Total-Count`: Total number of transactions matching the caller and filter criteria.
- `X-Page`: Current page index (0-based).
- `X-Page-Size`: Maximum entries per page.
- `X-Total-Pages`: Total number of pages available.

Invalid date formats or negative pagination values return `400 Bad Request` with error code `INVALID_QUERY_PARAMETER`.

## Investments — `/api/investments`

| Field | Type | Rules |
|---|---|---|
| `name` | string | required |
| `type` | `STOCKS` `MUTUAL_FUND` `FD` `CRYPTO` `GOLD` `OTHER` | required |
| `amountInvested` | number | required, `> 0` |
| `currentValue` | number | optional, `>= 0` |
| `notes` | string | optional |

Response: the same fields plus `id` and `createdAt`; `currentValue` and `notes` may be `null`.
Same five operations and status codes as transactions.

## Loans — `/api/loans`

| Field | Type | Rules |
|---|---|---|
| `name` | string | required |
| `type` | `HOME` `CAR` `PERSONAL` `EDUCATION` `OTHER` | required |
| `principal` | number | required, `> 0` |
| `emiAmount` | number | required, `> 0` |
| `interestRate` | number | required, `>= 0` (annual percent; stored with 4 decimals `numeric(10,4)`, resolving KI-026) |
| `startDate`, `endDate` | `yyyy-MM-dd` | optional |
| `active` | boolean | optional; `null`/absent means `true` |

Response: the same fields plus `id` and `createdAt`. Same five operations and status codes.

## Statement import — `/api/upload`

### `POST /api/upload/csv`

`multipart/form-data` with one part named `file`. Accepted: file name ending in `.csv` (case-insensitive),
non-empty, at most **5 MB** (`spring.servlet.multipart.max-file-size`), and at most **10,000 data rows** (the header line is not counted).
Content is read as UTF-8 (a leading byte-order mark `\uFEFF` is stripped) with automated fallback to `Windows-1252`
if strict UTF-8 decoding encounters malformed characters. Binary files (containing null bytes) are rejected with `400 Bad Request`.

All parsers use **dynamic header-based column mapping** (`HeaderDrivenStatementParser`), tolerating reordered columns and
arbitrary extra columns (resolving KI-003). Supported banks:
- **HDFC**: Separate withdrawal / deposit columns (`Date, Narration, ...`), `dd/MM/yy` default.
- **ICICI**: Supports both single amount with `Cr/Dr` column layout AND separate withdrawal / deposit layout (resolving KI-005), `dd/MM/yyyy` default.
- **SBI**: Separate debit / credit columns (`Txn Date, Description, Debit, Credit, ...`), `dd MMM yyyy` default.
- **Axis**: Separate DR / CR columns (`Tran Date, PARTICULARS, DR, CR, BAL`), `dd-MM-yyyy` default.
- **Kotak**: Separate debit / credit columns (`Transaction Date, Description, Debit Amount, Credit Amount, ...`), `dd-MM-yyyy` default.

Amount parsing handles Indian numbering (`1,23,456.78`), currency symbols (`₹`, `Rs.`), parenthesized negative amounts (`(1,234.50)`),
and trailing/leading `Dr`/`Cr` suffixes (resolving KI-006). Multi-pattern fallback date parsing automatically accommodates ISO and Indian bank formats.
**Note on verification**: Parsers are verified on synthetic fixtures and algorithmic layout permutations; compatibility with real proprietary bank exports remains unverified until real sample statements are supplied (KI-019).

`202 Accepted` — processing continues asynchronously in the background:
```json
{
  "jobId": "a4055cda-…",
  "status": "PENDING",
  "bankName": "HDFC",
  "originalFilename": "march.csv",
  "totalRows": 0,
  "rowsProcessed": 0,
  "duplicateRows": 0,
  "rejectedRows": 0,
  "skippedRows": 0,
  "rowErrors": [],
  "closingBalance": null,
  "statementFrom": null,
  "statementTo": null,
  "errorMessage": null,
  "createdAt": "2026-03-17T10:00:00.123456",
  "completedAt": null
}
```
`bankName` is the *detected* layout, or `UNKNOWN`.

Errors: `400` `The uploaded file is empty` / `Only .csv files are supported` / `The uploaded file appears to be a binary file, not a valid CSV statement.` / `The statement exceeds the maximum limit of 10,000 rows.` ·
`401` · `413` file over 5 MB (`MaxUploadSizeExceededException`, verified by `UploadErrorApiTest` — KI-029) ·
`503` import queue full (`ApiException.unavailable`, verified by `UploadErrorApiTest` — KI-029).

### `GET /api/upload/status/{jobId}`

`200` status response with current state and import statistics:
```json
{
  "jobId": "a4055cda-…",
  "status": "COMPLETED",
  "bankName": "HDFC",
  "originalFilename": "march.csv",
  "totalRows": 5,
  "rowsProcessed": 3,
  "duplicateRows": 0,
  "rejectedRows": 1,
  "skippedRows": 1,
  "rowErrors": [
    {
      "rowNumber": 8,
      "errorMessage": "Cannot parse date: 'notadate'",
      "snippet": "notadate,INVALID DATE ROW,0002..."
    }
  ],
  "classificationStatus": "COMPLETED",
  "classifiedRows": 2,
  "closingBalance": 75550.00,
  "statementFrom": "2026-03-01",
  "statementTo": "2026-03-05",
  "errorMessage": null,
  "createdAt": "2026-03-17T10:00:00.123456",
  "completedAt": "2026-03-17T10:00:02.345678"
}
```
`404` if the job does not exist or belongs to another user.

**Row rules (end-to-end audit):** dates are parsed strictly (a date that does not exist, such as `31/02/26`, is a rejected row, never moved) and must lie between 1990-01-01 and tomorrow; a negative or parenthesised amount in a *credit* column, or next to a Cr/Dr indicator, is a rejected row (the direction would be a guess), while a negative or parenthesised *debit* is an expense (KI-006); narrations longer than 255 characters are truncated; amounts above ₹10 trillion are rejected for that row only; two statements imported at the same time are safe (rows already stored by the other import count as duplicates).

`classificationStatus` / `classifiedRows` describe automatic categorisation of the imported rows and are
**independent of `status`**: transactions are saved first, then categorised, and an import is `COMPLETED` even
when the AI service is unavailable. `classificationStatus`: `NOT_ATTEMPTED` (nothing new to classify, or the job
predates 0.5.0) · `COMPLETED` (every request answered; the service may still have declined some rows) ·
`PARTIAL` (some batches failed or were skipped) · `UNAVAILABLE` (nothing usable came back). `classifiedRows`
is how many imported transactions received a category; duplicates skipped by fingerprint are never re-classified,
so a category you corrected is never touched by a re-upload.

| `status` | Meaning |
|---|---|
| `PENDING` | accepted, worker has not started |
| `PROCESSING` | parsing / classifying / saving |
| `COMPLETED` | import finished; row counts and reconciliation metrics available; `completedAt` set |
| `FAILED` | import aborted; `errorMessage` contains user-actionable reason |

**Reconciliation Invariant:**
$$\text{totalRows} = \text{rowsProcessed} + \text{duplicateRows} + \text{rejectedRows} + \text{skippedRows}$$
- `totalRows`: Total number of data rows in the statement (excluding header and top metadata).
- `rowsProcessed`: Number of transactions successfully parsed and persisted.
- `duplicateRows`: Legitimate transactions skipped because their SHA-256 fingerprint already exists in the user's ledger.
- `rejectedRows`: Malformed rows skipped due to invalid amounts or dates, detailed in `rowErrors`.
- `skippedRows`: Non-financial or zero-amount rows intentionally skipped.
- `rowErrors`: Array of `{ rowNumber, errorMessage, snippet }` with long numeric sequences masked (e.g. `1234****5678`) to sanitize PII and account numbers.
- `closingBalance`: Final statement balance extracted from balance column when present.
- `statementFrom` / `statementTo`: Bounding calendar dates of transactions in the statement.

On application restart, any jobs left in `PENDING` or `PROCESSING` state are automatically transitioned to `FAILED` with `"Import was interrupted by a system restart. Please re-upload your statement."` (resolving KI-007). Because deduplication is idempotent, re-upload is completely safe.

### `GET /api/upload/jobs`

Returns `200 OK` with a JSON array of all upload jobs owned by the authenticated caller, ordered by `createdAt DESC`. Scoped strictly to the authenticated user.
```json
[
  {
    "jobId": "a4055cda-…",
    "status": "COMPLETED",
    "bankName": "HDFC",
    "originalFilename": "march.csv",
    "totalRows": 5,
    "rowsProcessed": 3,
    "duplicateRows": 0,
    "rejectedRows": 1,
    "skippedRows": 1,
    "rowErrors": [...],
    "closingBalance": 75550.00,
    "statementFrom": "2026-03-01",
    "statementTo": "2026-03-05",
    "errorMessage": null,
    "createdAt": "2026-03-17T10:00:00.123456",
    "completedAt": "2026-03-17T10:00:02.345678"
  }
]
```

## Financial Intelligence & Analytics — `/api/analytics`

All analytics endpoints require `Authorization: Bearer <access token>`, enforce strict authenticated user isolation, and perform exact monetary calculations server-side using `BigDecimal` (scale 2, `RoundingMode.HALF_UP`).

Query Parameters (supported across all endpoints):

| Parameter | Type | Description |
|---|---|---|
| `from` | `yyyy-MM-dd` | Inclusive start date. Optional. |
| `to` | `yyyy-MM-dd` | Inclusive end date. Optional. |

If `from` is after `to`, every endpoint returns `400 Bad Request` with an explanatory validation error.

### Financial Correctness Guarantees:
1. **Exclusions**: `TRANSFERS`, `CARD_PAYMENTS`, and `INVESTMENTS` are non-spending flows and excluded from both income and expense calculations to prevent double-counting.
2. **Refund Treatment**: `REFUNDS` are subtracted directly from gross expenses (`netExpenses = grossExpenses - refunds`), never counted as earned income.
3. **Cash Flow Identity**: $\text{netCashFlow} = \text{totalIncome} - \text{netExpenses}$ holds exactly across summary and monthly aggregations.
4. **Savings Rate**: $\text{savingsRate} = ((\text{totalIncome} - \text{netExpenses}) / \text{totalIncome}) \times 100$. Calculated only when recorded income $> 0$. When income is 0 or unrecorded, `savingsRate` returns `null` with `savingsRateApplicable: false` and an explainable caveat string.
5. **No Hallucinated Data**: Only persisted transactions, active loans, and tracked investments owned by the caller are included.

---

### `GET /api/analytics/summary`

Returns top-level financial KPIs, month-over-month comparisons, investment/debt rollups, and data-quality summaries.

`200 OK` →
```json
{
  "period": { "from": "2026-03-01", "to": "2026-03-31" },
  "totalIncome": 120000.00,
  "grossExpenses": 45000.00,
  "totalRefunds": 2000.00,
  "totalExpenses": 43000.00,
  "netCashFlow": 77000.00,
  "savingsRate": 64.17,
  "savingsRateApplicable": true,
  "savingsRateCaveat": null,
  "totalInvested": 300000.00,
  "totalCurrentValue": 345000.00,
  "unrealizedGain": 45000.00,
  "gainPercentage": 15.00,
  "activeLoansCount": 1,
  "totalMonthlyEmi": 18500.00,
  "totalPrincipal": 100000.00,
  "momComparison": {
    "previousIncome": 100000.00,
    "incomeChange": 20000.00,
    "incomeChangePct": 20.00,
    "previousExpenses": 50000.00,
    "expenseChange": -7000.00,
    "expenseChangePct": -14.00,
    "previousNet": 50000.00,
    "netChange": 27000.00
  },
  "excludes": ["TRANSFERS", "CARD_PAYMENTS", "INVESTMENTS"],
  "dataQuality": {
    "totalTransactions": 60,
    "uncategorizedCount": 2,
    "uncategorizedPercentage": 3.33,
    "hasSufficientHistory": true
  },
  "topInsights": [ ... ]
}
```

---

### `GET /api/analytics/monthly`

Returns an ordered calendar-month time series of inflows, gross expenses, refunds, net expenses, net cash flow, and savings rates.

`200 OK` →
```json
[
  {
    "month": "2026-02",
    "income": 100000.00,
    "grossExpenses": 50000.00,
    "refunds": 0.00,
    "expenses": 50000.00,
    "netCashFlow": 50000.00,
    "savingsRate": 50.00,
    "transactionCount": 24,
    "momExpenseChangePct": null,
    "momIncomeChangePct": null
  },
  {
    "month": "2026-03",
    "income": 120000.00,
    "grossExpenses": 45000.00,
    "refunds": 2000.00,
    "expenses": 43000.00,
    "netCashFlow": 77000.00,
    "savingsRate": 64.17,
    "transactionCount": 32,
    "momExpenseChangePct": -14.00,
    "momIncomeChangePct": 20.00
  }
]
```

---

### `GET /api/analytics/categories`

Returns expense breakdown across classified canonical categories, percentage of net spending, transaction counts, average size, and month-over-month trend indicators.

`200 OK` →
```json
{
  "period": { "from": "2026-03-01", "to": "2026-03-31" },
  "categories": [
    {
      "category": "HOUSING",
      "label": "Housing",
      "flow": "EXPENSE",
      "amount": 25000.00,
      "percentage": 58.14,
      "transactionCount": 1,
      "averageTransactionAmount": 25000.00,
      "momChangePct": 0.00,
      "isIncreasing": false,
      "largestTransaction": {
        "id": "tx-1",
        "title": "Apartment Rent",
        "amount": 25000.00,
        "date": "2026-03-01",
        "category": "HOUSING"
      }
    }
  ],
  "totalGrossExpenses": 45000.00,
  "totalRefunds": 2000.00,
  "totalNetExpenses": 43000.00,
  "largestCategory": "HOUSING",
  "risingCategories": ["GROCERIES"]
}
```

---

### `GET /api/analytics/recurring`

Detects potential periodic payment commitments (subscriptions, bills, rent, SIPs) using transaction title normalization, amount variance checking ($\le 15\%$), and interval clustering (monthly $\approx 30$ days, weekly $\approx 7$ days).

`200 OK` →
```json
{
  "period": { "from": "2026-01-01", "to": "2026-03-31" },
  "items": [
    {
      "id": "rec-netflix",
      "title": "Netflix",
      "merchant": "Netflix",
      "category": "SUBSCRIPTIONS",
      "amount": 649.00,
      "cadence": "MONTHLY",
      "occurrences": 3,
      "lastDate": "2026-03-15",
      "confidence": "HIGH",
      "isConfirmed": false,
      "explanation": "Regular monthly debits of ~₹649 detected across 3 cycles."
    }
  ],
  "totalMonthlyRecurring": 649.00,
  "recurringPercentageOfExpenses": 1.51
}
```

---

### `GET /api/analytics/data-quality`

Exposes data coverage diagnostics, ledger timeline spans, missing month gaps, and uncategorized transaction volume so partial data is never mistaken for a complete financial picture.

`200 OK` →
```json
{
  "coverageFrom": "2026-01-01",
  "coverageTo": "2026-03-31",
  "totalDaysCovered": 90,
  "totalMonthsCovered": 3,
  "totalTransactions": 60,
  "uncategorizedCount": 2,
  "uncategorizedPercentage": 3.33,
  "missingMonths": [],
  "hasSufficientHistory": true,
  "caveats": [
    "2 uncategorized transactions (₹1,500.00) excluded from category breakdowns."
  ]
}
```

---

### `GET /api/analytics/insights`

Returns explainable, deterministic financial observations and statistical spending outliers. Every insight specifies the triggering rule, supporting figures, and confidence caveats.

`200 OK` →
```json
{
  "period": { "from": "2026-03-01", "to": "2026-03-31" },
  "insights": [
    {
      "id": "ins-savings-rate",
      "title": "Strong Savings Rate: 64.2%",
      "type": "POSITIVE",
      "message": "You saved 64.2% of your recorded income (₹77,000 net surplus).",
      "period": "Selected Period",
      "supportingFigures": { "savingsRate": 64.17 },
      "ruleTriggered": "SAVINGS_RATE_STRONG",
      "confidenceCaveat": null
    }
  ],
  "unusualTransactions": [
    {
      "transactionId": "tx-outlier-1",
      "title": "Croma Electronics",
      "amount": 28000.00,
      "date": "2026-03-18",
      "category": "SHOPPING",
      "benchmarkAmount": 3500.00,
      "ratio": 8.00,
      "reason": "Transaction amount (₹28,000.00) is 8.0x the median category expense (₹3,500.00).",
      "rule": "OUTLIER_VS_MEDIAN"
    }
  ]
}
```

## Budgets — `/api/budgets`

All budget amounts are positive `BigDecimal` numbers with scale 2. Budget periods are represented by `yearMonth` formatted as `YYYY-MM`. Each budget belongs strictly to the authenticated user.

### `GET /api/budgets`
List all budgets for the authenticated user, optionally filtered by `yearMonth`.

Query Parameters:
- `yearMonth` (optional, string `YYYY-MM`): Filter by month.

Response `200`: Array of `BudgetDto`:
```json
[
  {
    "id": "bgt-1",
    "yearMonth": "2026-10",
    "category": null,
    "amount": 50000.00,
    "createdAt": "2026-10-01T10:00:00Z",
    "updatedAt": "2026-10-01T10:00:00Z"
  },
  {
    "id": "bgt-2",
    "yearMonth": "2026-10",
    "category": "DINING",
    "amount": 8000.00,
    "createdAt": "2026-10-01T10:00:00Z",
    "updatedAt": "2026-10-01T10:00:00Z"
  }
]
```

### `GET /api/budgets/overview`
Get comprehensive monthly budget performance, comparing actual spending with budgeted limits.

Query Parameters:
- `yearMonth` (optional, string `YYYY-MM`, defaults to current calendar month).

Response `200`: `MonthlyBudgetOverviewResponse`:
```json
{
  "yearMonth": "2026-10",
  "overall": {
    "budgetId": "bgt-1",
    "budgetAmount": 50000.00,
    "actualSpending": 32450.00,
    "remainingAmount": 17550.00,
    "percentageUsed": 64.90,
    "status": "UNDER_BUDGET"
  },
  "categoryBudgets": [
    {
      "budgetId": "bgt-2",
      "category": "DINING",
      "budgetAmount": 8000.00,
      "actualSpending": 7200.00,
      "remainingAmount": 800.00,
      "percentageUsed": 90.00,
      "status": "APPROACHING_LIMIT",
      "transactionCount": 12
    }
  ],
  "unbudgetedSpending": 5400.00,
  "totalSpending": 32450.00,
  "warnings": [
    "DINING has reached 90.0% of its budget limit."
  ]
}
```
Status values:
- `UNDER_BUDGET`: $\text{percentageUsed} < 80\%$
- `APPROACHING_LIMIT`: $80\% \le \text{percentageUsed} \le 100\%$
- `OVER_BUDGET`: $\text{percentageUsed} > 100\%$

### `POST /api/budgets`
Create an overall or category budget for a specified month.
```json
{
  "yearMonth": "2026-10",
  "category": "DINING",
  "amount": 8000.00
}
```
- `yearMonth`: required, format `YYYY-MM`.
- `category`: optional. If `null` or omitted, sets the **overall** monthly budget. If specified, must be an active expense category from the taxonomy.
- `amount`: required, `> 0`.
- Response: `201 Created` with `BudgetDto`. Duplicate budget for the same user, month, and category returns `409 Conflict`.

### `PUT /api/budgets/{id}`
Update the budgeted amount of an existing budget.
```json
{
  "amount": 9500.00
}
```
- Response: `200 OK` with updated `BudgetDto`. Returns `404 Not Found` if budget id belongs to another user.

### `DELETE /api/budgets/{id}`
Delete a budget.
- Response: `204 No Content`. Returns `404 Not Found` if budget id belongs to another user.

---

## Financial Goals — `/api/goals`

Savings goals track personal targets and manual contributions.

> [!NOTE]
> **Banking Disclaimer:** Goal contributions recorded here represent self-tracked savings allocations for financial planning and target pacing. They do NOT represent verified bank account balances or automated bank transfers.

### `GET /api/goals`
List all active and archived goals for the authenticated user.

Response `200`: Array of `GoalResponseDto`:
```json
[
  {
    "id": "goal-1",
    "name": "Emergency Fund",
    "description": "6 months of living expenses",
    "targetAmount": 150000.00,
    "currentAmount": 60000.00,
    "targetDate": "2027-03-31",
    "status": "ACTIVE",
    "progressPercentage": 40.00,
    "remainingAmount": 90000.00,
    "isCompleted": false,
    "isOverdue": false,
    "requiredMonthlyContribution": 18000.00,
    "contributionCount": 4,
    "createdAt": "2026-10-01T10:00:00Z",
    "updatedAt": "2026-10-24T10:00:00Z"
  }
]
```

### `GET /api/goals/{id}`
Get detailed goal information including complete contribution history.

Response `200`: `GoalDetailResponse`:
```json
{
  "goal": { ... },
  "contributions": [
    {
      "id": "gc-1",
      "goalId": "goal-1",
      "amount": 15000.00,
      "contributionDate": "2026-10-15",
      "note": "October savings allocation",
      "createdAt": "2026-10-15T12:00:00Z"
    }
  ],
  "disclaimer": "Recorded contributions are self-tracked allocations for planning purposes only, not verified bank deposits."
}
```

### `POST /api/goals`
Create a new savings goal.
```json
{
  "name": "Emergency Fund",
  "description": "6 months of living expenses",
  "targetAmount": 150000.00,
  "targetDate": "2027-03-31"
}
```
- `name`: required, 1-100 characters.
- `targetAmount`: required, positive `BigDecimal`.
- `targetDate`: optional, `YYYY-MM-DD`.
- `description`: optional, max 500 characters.
- Response: `201 Created` with `GoalResponseDto`.

### `PUT /api/goals/{id}`
Update goal metadata or archive status.
```json
{
  "name": "Emergency Fund (Updated)",
  "description": "Expanded to 8 months of living expenses",
  "targetAmount": 200000.00,
  "targetDate": "2027-06-30",
  "status": "ACTIVE"
}
```
- `status`: optional `ACTIVE`, `COMPLETED`, `ARCHIVED`.
- Response: `200 OK` with updated `GoalResponseDto`.

### `DELETE /api/goals/{id}`
Delete a goal and all its recorded contributions (cascading).
- Response: `204 No Content`.

### `POST /api/goals/{id}/contributions`
Record a contribution toward a goal. Protected by database row locking (`SELECT ... FOR UPDATE`) to prevent concurrent race conditions.
```json
{
  "amount": 10000.00,
  "contributionDate": "2026-10-24",
  "note": "Bonus allocation"
}
```
- `amount`: required, `> 0`.
- `contributionDate`: optional, defaults to today.
- `note`: optional, max 255 characters.
- If `currentAmount + amount >= targetAmount`, status transitions automatically to `COMPLETED`.
- Response: `201 Created` with `GoalContributionDto`.

### `DELETE /api/goals/{id}/contributions/{contributionId}`
Delete a recorded contribution. Atomically subtracts `amount` from `currentAmount` under row lock. If goal was previously `COMPLETED` and `currentAmount < targetAmount`, status reverts to `ACTIVE`.
- Response: `204 No Content`.

## Forecasting — `/api/forecast`

*(v0.8.0; calculated on demand from the persisted ledger, owner-scoped, nothing stored, so there is no cache to invalidate.)*
All six endpoints require authentication (`401` otherwise) and see only the caller's own data. Errors use the standard
`ApiError`. Method, assumptions, evidence rules and the evaluation results are in
[FINANCIAL_RULES.md §7](FINANCIAL_RULES.md#7-forecasting-rules-v080) and [TESTING.md](TESTING.md#7-forecast-evaluation).

**Conventions shared by every forecast response**

| Field | Meaning |
|---|---|
| `reliability` | `RELIABLE` · `LIMITED` · `UNAVAILABLE`. How much *evidence* the forecast rests on, **not** how accurate it will be. `UNAVAILABLE` means nothing is projected: the lists are empty and the totals are `null` — never zeros. |
| `method` / `algorithmVersion` | `LEVEL_DAMPED_TREND` / `2.0.0`. Statistical and rule-based; no machine learning. |
| `asOf` | The date the calculation treats as "today" (server clock). |
| `horizonMonths` | 1 – 12 (otherwise `400`). Projected months are the months **after** the current one. |
| `history` | `from`/`to` (first and last complete month used), `monthsUsed`, the thresholds (`minMonthsLimited` 3, `minMonthsReliable` 6), `missingMonths` (complete months in range with no usable data: *unknown, not zero*), `transactionsUsed`, `firstMonthPossiblyPartial`. |
| `warnings` | Data-quality problems found (gaps, stale import, partial first month, uncategorised share, unusual months that were limited, irregular or missing income, low volume). |
| `assumptions` | What the method assumed, in plain language. |
| `Estimate` | `{ point, lower, upper }`. `lower`/`upper` are the 10th and 90th percentile of the method's **own past forecast errors** on this history, added to `point`; both `null` when fewer than 8 past errors exist at that distance. **Not a calibrated probability.** |

Parameter of the wrong type (`horizonMonths=abc`) is `400`, not `500`.

### `GET /api/forecast/cash-flow?horizonMonths=3`

Observed monthly income, spending and net, the month in progress (shown, never used to train), and projected months.
```json
{
  "reliability": "RELIABLE", "method": "LEVEL_DAMPED_TREND", "algorithmVersion": "2.0.0", "asOf": "2026-07-15", "horizonMonths": 3,
  "history": { "from": "2026-01", "to": "2026-06", "monthsUsed": 6, "minMonthsLimited": 3, "minMonthsReliable": 6,
               "missingMonths": [], "transactionsUsed": 36, "firstMonthPossiblyPartial": false },
  "warnings": [], "assumptions": ["…"], "intervalNote": "Bands (when shown) span the 10th to 90th percentile …",
  "incomeAvailable": true,
  "historical": [ { "month": "2026-06", "income": 50000.00, "expenses": 30000.00, "net": 20000.00 } ],
  "currentMonth": { "month": "2026-07", "income": 50000.00, "expenses": 5000.00, "net": 45000.00, "note": "Recorded so far this month. Not used to train …" },
  "projected": [ {
    "month": "2026-08",
    "income":   { "point": 50000.00, "lower": null, "upper": null },
    "expenses": { "point": 30000.00, "lower": 28000.00, "upper": 32500.00 },
    "net":      { "point": 20000.00, "lower": null, "upper": null },
    "recurringFloorApplied": false, "factors": [] } ],
  "summary": { "totalIncome": 150000.00, "totalExpenses": 90000.00, "totalNet": 60000.00, "averageMonthlyNet": 20000.00, "savingsRatePercent": 40.00 },
  "recurring": [ { "id": "rec-1", "title": "Netflix subscription", "category": "SUBSCRIPTIONS", "monthlyAmount": 500.00,
                   "cadence": "MONTHLY", "confidence": "HIGH", "userConfirmed": false, "includedInBaseline": true } ]
}
```
- Fewer than 3 usable months: `reliability: "UNAVAILABLE"`, `projected: []`, `summary: null`; `historical` still lists what exists.
- Income observed in fewer than 3 usable months: `incomeAvailable: false`; each projected `income` and `net` is `null` and `summary.totalIncome/totalNet/averageMonthlyNet/savingsRatePercent` are `null`. Spending is still projected. Zero is never substituted.
- `recurring` are detected active recurring payments (monthly/weekly, confirmed or ≥ 3 sightings at HIGH/MEDIUM confidence, seen within 45 days). They are **already part of the observed history** (`includedInBaseline`), are not added again, and only raise a projection that would fall below their total (`recurringFloorApplied`). Annual and irregular payments are not detected.
- Net bands come from the past errors of income minus spending at the same distance, not from combining the two bands.

### `GET /api/forecast/categories?horizonMonths=3`

Per-category projected monthly spending. A category needs spending in at least 3 usable months for its own forecast; others appear in `withoutForecast` with the reason.
```json
{ "reliability": "RELIABLE", "…": "common fields",
  "categories": [ {
    "category": "DINING", "label": "Dining", "activeMonths": 6, "recentMonthlyAverage": 5000.00,
    "monthly": [ { "point": 6576.00, "lower": 6000.00, "upper": 7100.00 } ], "projectedTotal": 20576.00,
    "trend": "UPWARD", "trendPercentPerMonth": 8.0, "reliability": "LIMITED", "recurringMonthly": 0.00,
    "outlierMonths": [], "explanation": "…" } ],
  "withoutForecast": [ { "category": "ENTERTAINMENT", "label": "Entertainment", "reason": "Spending in only 1 of 6 month(s); at least 3 are needed for a category forecast." } ],
  "risingCategories": ["DINING"] }
```
`trend` is `UPWARD`/`DOWNWARD` only when a trend is statistically significant (≥ 6 months, |t| ≥ 2) and `STABLE` otherwise (`NOT_ESTABLISHED` with under 6 months). `risingCategories` additionally requires ≥ 3 % a month. Categories use the analytics definitions: transfers, card payments and investments are excluded; credits filed under the same expense category net against it; refunds filed under the generic *Refunds* category cannot be attributed to a category.

### `GET /api/forecast/budget-overruns?yearMonth=2026-07`

Month-end outlook against the v0.7.0 budgets (defaults to the current month; allowed range: 36 months back to 12 ahead, else `400`; a malformed value is `400`).
```json
{ "month": "2026-07", "asOf": "2026-07-15", "period": "CURRENT", "daysInMonth": 31, "daysWithRecordedData": 10, "daysRemaining": 21,
  "warnings": [],
  "overall": { "budgetId": "…", "category": null, "label": "Overall monthly spending", "budgetAmount": 28000.00, "actualToDate": 6000.00,
    "remainingBudget": 22000.00, "recurringStillDue": 0.00, "expectedLow": 18600.00, "expectedCentral": 24300.00, "expectedHigh": 30000.00,
    "estimatedOverrun": 0.00, "risk": "AT_RISK", "rule": "The estimates span ₹18600.00 to ₹30000.00 …", "basis": ["RUN_RATE", "HISTORY"],
    "indeterminateReason": null, "factors": ["Recorded so far: …", "Run-rate: …", "History: …"] },
  "categories": [ ] }
```
`actualToDate` equals `actualSpend` of `GET /api/budgets/overview` (same definition). Two independent month-end estimates are formed when their evidence exists:
`RUN_RATE` (≥ 7 recorded days and ≥ 5 transactions in scope (≥ 3 for a category): recorded spending + recurring still due + the non-recurring daily rate × remaining days) and `HISTORY` (≥ 3 complete months: median of the last up to 6, never below recorded + recurring due). Paid recurring payments are removed from the daily rate and unpaid ones added once.

| `risk` | Rule |
|---|---|
| `EXCEEDED` | `actualToDate` > budget (a fact). |
| `LIKELY_OVER` | Recorded spending plus recurring still due already exceeds the budget, **or** at least two estimates are available and the lowest is above the budget. |
| `AT_RISK` | Not the above, and the highest estimate is above 90 % of the budget. A single extrapolation can never raise more than this. |
| `ON_TRACK` | The highest estimate is ≤ 90 % of the budget. |
| `INDETERMINATE` | No estimate possible; `expected*` and `estimatedOverrun` are `null` and `indeterminateReason` says why. |
| `COMPLETED_WITHIN` / `COMPLETED_OVER` | The month is over; final spend against the budget. |

### `GET /api/forecast/savings?horizonMonths=6`

Projected cumulative **cash-flow surplus**, kept apart from self-tracked goal contributions and (absent) bank balances.
```json
{ "reliability": "RELIABLE", "…": "common fields", "disclaimer": "Projected surplus … is not a bank balance …", "incomeAvailable": true,
  "averageMonthlyNet": 20000.00, "projectedSavingsRatePercent": 40.00,
  "points": [ { "month": "2026-08", "net": { "point": 20000.00, "lower": null, "upper": null }, "cumulativeNet": 20000.00 } ],
  "goals": [ { "id": "…", "name": "Emergency fund", "targetAmount": 60000.00, "recordedAmount": 6000.00, "remaining": 54000.00, "targetDate": "2026-12-31",
               "requiredMonthlyPace": 10800.00, "monthsIfAllSurplusUsed": 3, "completionMonthIfAllSurplusUsed": "2026-10", "onPaceIfAlone": true } ],
  "accounting": { "projectedSurplusOverHorizon": 60000.00, "recordedGoalContributions": 6000.00, "verifiedBankBalance": null,
                  "verifiedBankBalanceNote": "Not available. FinGuard does not hold or verify account balances.",
                  "totalRequiredGoalPace": 10800.00, "goalsOvercommitted": false } }
```
`UNAVAILABLE` when there is too little history **or no observed income** (no surplus can be projected). `recordedAmount` and `recordedGoalContributions` are self-tracked planning figures, not deposits. Each goal's "if all surplus used" figures assume the whole surplus goes to that goal alone; `goalsOvercommitted` compares the required pace of **all** dated goals together with the projected monthly surplus. No band is given for the running total.

### `POST /api/forecast/scenarios/simulate`

**v0.9.0 additions (optional request fields):** `loanAdjustments` (max 10: `{loanId, action: CLOSE|PREPAY|CHANGE_EMI, amount}`; `amount` required for
PREPAY and CHANGE_EMI; one adjustment per loan; the loan must belong to the caller, be active, have a start date and not be repaid -> otherwise 400) and
`oneTimeItems` (max 20: `{title, kind: INCOME|EXPENSE, month: 1..12, amount}`; `month` must not exceed `horizonMonths`). The response gains `loanEffects[]`
(`loanId, loanName, action, estimatedOutstandingBalance, baselineMonthsToPayoff, scenarioMonthsToPayoff, baselineRemainingInterest, scenarioRemainingInterest,
interestSaved, cashOutInHorizonChange, notes`). Rules: [FINANCIAL_RULES.md section 8](FINANCIAL_RULES.md#8-loan-one-time-and-saved-scenario-rules-v090).


Hypothetical what-if. Reads the ledger, **writes nothing** (verified by a test that compares transactions, budgets and goals before and after).
```json
{ "name": "optional, ≤ 80 chars", "horizonMonths": 6,
  "incomeAdjustment": { "type": "PERCENTAGE", "value": 10 },
  "categoryAdjustments": [ { "category": "GROCERIES", "type": "PERCENTAGE", "value": -20 } ],
  "addedRecurringExpenses": [ { "title": "Gym", "category": null, "monthlyAmount": 1500 } ],
  "removedRecurringExpenseIds": ["rec-1"] }
```
Validation (`400`): `horizonMonths` 1–12; percentages −100…500; fixed amounts ±10,000,000 a month; `monthlyAmount` > 0; ≤ 20 category changes and added expenses, ≤ 50 removals; a category must be a spending category (not income, transfers, card payments, investments or refunds) and appear once; removed ids must be currently detected recurring payments; an income change needs an observed income baseline.
Response: `{ name, hypothetical: true, disclaimer, available, unavailableReason, baselineReliability, horizonMonths, incomeAvailable, warnings, baseline, scenario, delta, months[], categories[], explanations[] }` with `baseline`/`scenario` = `{ income, expenses, net, savingsRatePercent }` and `delta` = `{ income, expenses, net, savingsRatePoints }` over the horizon. With no baseline forecast `available` is `false`, all of `baseline/scenario/delta` are `null` and `unavailableReason` says why. Adjustments are flat monthly changes; a category cannot go below zero (a warning says so); removing a recurring payment reduces its category once. Scenario figures are point estimates: no band.

### `GET /api/forecast/backtest`

How the method would have done on the **caller's own** history, by chronological rolling origin: each forecast uses only months before its origin. For `EXPENSES`, `INCOME` and `NET`, horizons 1, 2, 3 and 6 (those that can be scored): `samples`, and for the method and two baselines (`naive` = last month repeats, `mean3` = mean of the last three) the `mae`, `wapePercent` (`null` when every actual is zero), `rmse`, `bias`; `verdict` is `INCONCLUSIVE` below 6 samples, otherwise `MODEL_BETTER`/`SIMILAR`/`BASELINE_BETTER` by MAE with a 10 % margin. `status: "INSUFFICIENT_DATA"` below 4 usable months. `evaluatedOn` is always `USER_HISTORY`; the synthetic evaluation is documented separately.

### Saved scenarios — `/api/scenarios` (v0.9.0)

Calculating without keeping anything stays at `POST /api/forecast/scenarios/simulate`. These endpoints keep a scenario the user wants to return to. All are authenticated and
owner-scoped (another user's id is `404`).

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/api/scenarios` | Body = the same request as `simulate`, with a required `name` (<= 80). Runs the validated simulation once and stores the request and the result snapshot with the method version. `201` + `{ summary, request, snapshot }`. `400` when the name is blank, any adjustment is invalid, or the baseline is unavailable (nothing is saved). `409` after 50 saved scenarios. |
| `GET` | `/api/scenarios` | Newest first: `[{ id, name, horizonMonths, methodVersion, createdAt, netChange, spendingChange }]` (changes from the snapshot; `null` without an income baseline). |
| `GET` | `/api/scenarios/{id}` | `{ summary, request, snapshot }` — the stored adjustments and result. |
| `POST` | `/api/scenarios/{id}/rerun` | Recalculates on the **current** ledger: `{ summary, request, snapshot, current, unchanged, methodVersionChanged, currentMethodVersion }`. `unchanged` is `true` only when the fresh result is byte-identical to the snapshot and the method version is the same. Never overwrites the snapshot. |
| `DELETE` | `/api/scenarios/{id}` | `204`. |

## Recommendations — `/api/recommendations` (v0.10.0)

### `GET /api/recommendations`

General guidance computed on demand from the caller's own records for the **last 3 complete calendar months** (the current month is never used). Authenticated, owner-scoped, read-only, nothing stored.
No parameters.

Response: `{ period: { from, to, months, monthsWithData }, disclaimer, healthy, recommendations[], notEvaluated[], thresholds }`.

- `recommendations[]` is ordered by severity (`CRITICAL`, `HIGH`, `MEDIUM`, `INFO`), then by `estimatedMonthlyImpact` (largest first; items without an estimate last), then by id. Each item:
  `{ id, severity, title, explanation, suggestedAction, evidence[], estimatedMonthlyImpact, scenarioPreset }`.
- `evidence[]` = `{ metric, value, unit, source }`; `source` is the analytics endpoint **with the same `from`/`to`** the number can be reproduced from
  (`/api/analytics/summary`, `/monthly`, `/recurring`, `/data-quality`, or `/api/investments`, `/api/loans`).
- `scenarioPreset` = `{ label, request }` where `request` is a ready body for `POST /api/forecast/scenarios/simulate` (a 10 % cut of the largest discretionary category), or `null`.
- `notEvaluated[]` = `{ id, title, reason }`: rules that could not run because their input is missing (too few months, no income, too few transactions, no consecutive months) and the
  emergency-fund check, which can never run because FinGuard holds no balances. A rule is never guessed.
- `healthy` is `true` only when at least the minimum months of records exist and no item above `INFO` was produced; with too little data it is `false` and nothing is recommended.
- `thresholds` echoes the active values. Rules and defaults: [FINANCIAL_RULES.md section 9](FINANCIAL_RULES.md#9-recommendation-rules-v0100).

## Errors

Every error body has the same shape (`ApiError`):

```json
{ "timestamp": "2026-03-17T10:00:00.123Z", "status": 400, "error": "Bad Request",
  "message": "Validation failed", "fieldErrors": { "email": "must be a well-formed email address" } }
```
`fieldErrors` is present only for validation failures. The web app displays the first field error, else `message`.

| Status | When |
|---|---|
| 400 | Validation failed; malformed JSON; missing request part; empty or non-CSV upload |
| 401 | Missing, invalid, expired or wrong-type bearer token (`Authentication required`); bad login; bad refresh token |
| 403 | Defined (`Access denied`) but not reachable today — there are no roles |
| 404 | Unknown route (`NoResourceFoundException`); unknown id or id owned by another user |
| 405 | HTTP method not supported for endpoint (`HttpRequestMethodNotSupportedException`) |
| 406 | Media type cannot be satisfied by Accept header (`HttpMediaTypeNotAcceptableException`) |
| 409 | Duplicate email on register; database uniqueness violation |
| 413 | Upload too large (> 5 MB, `MaxUploadSizeExceededException`) |
| 415 | Unsupported request content type (`HttpMediaTypeNotSupportedException`) |
| 429 | Too many authentication attempts (`RateLimitExceededException`); response includes `Retry-After: <seconds>` header |
| 500 | Unexpected error — generic message, details only in the server log |
| 503 | Import queue full |

## CORS

Allowed origins come from `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`); methods `GET POST PUT PATCH DELETE
OPTIONS`; any header; credentials allowed.

## Internal contract: backend → AI service

Not part of the public API. The backend's `AiClient` calls the Python service. Both sides are implemented and are
tested against each other: Java replays the recorded exchange in
[`contracts/classify-examples.json`](../contracts/classify-examples.json), Python verifies the same file against the
live classifier, and `LiveAiServiceContractTest` / `LiveAiImportTest` start the real FastAPI process.

`POST {AI_SERVICE_URL}/v1/classify` — header `X-Internal-Token: <AI_INTERNAL_TOKEN>` (see below)
```json
{ "transactions": [
  { "id": "6c1f…", "description": "UPI-SWIGGY-swiggy@icici", "type": "EXPENSE", "amount": 450.00 } ] }
```
| Field | Rules |
|---|---|
| `transactions` | 0 – 500 items (`422` above that). The backend sends batches of 200 (`ai.service.batch-size`, max 500). |
| `id` | required, 1 – 64 chars, unique within the request. |
| `description` | required, ≤ 1000 chars (the backend truncates longer narrations). May be empty. |
| `type` | optional `INCOME` (credit) \| `EXPENSE` (debit). Categories that do not suit the direction are never returned. |
| `amount` | optional, `≥ 0`. Accepted for forward compatibility; the rule baseline ignores it. |

Response `200`:
```json
{ "model_version": "rules-1.0.0",
  "results": [ {
    "id": "6c1f…", "category": "DINING", "confidence": 0.75, "confidence_calibrated": false,
    "method": "rule", "model_version": "rules-1.0.0", "rule_id": "dining.swiggy",
    "needs_review": false, "reason": "Matched rule dining.swiggy", "alternatives": [] } ] }
```
One result per input id, in order. `method` is `rule`, `model` (no model exists yet) or **`none`: the service
abstained** — then `category` is `UNCATEGORIZED`, `confidence` is `0`, `needs_review` is `true`, and `reason` says
why (e.g. `UPI payee is not a recognised merchant`). `confidence` is a rule-strength tier and is a probability
**only if** `confidence_calibrated` is `true`. `alternatives` lists up to 3 other candidate categories when rules
disagreed. Errors: `422` invalid body, `401` missing/wrong token. Transaction descriptions are never logged by the
Python service.

**Authentication.** When `AI_INTERNAL_TOKEN` is set on the Python service it requires the same value in
`X-Internal-Token` (constant-time comparison) on `/v1/*`; `/health` stays open. The backend sends the header when
`ai.service.internal-token` (env `AI_INTERNAL_TOKEN`) is set. **When the variable is empty the check is disabled**
(local development) — set it in every shared environment (KI-033).

**Client behaviour (`AiClient`).** Timeouts 5 s connect / 30 s read. Every prediction is validated before use:
the id must belong to the request, the confidence must be finite and within [0, 1], the method must be known;
the backend additionally requires `category` to be a taxonomy id that is not `UNCATEGORIZED` and suits the
transaction's direction. Invalid predictions are dropped, not applied. A failed batch (timeout, refused
connection, HTTP error, malformed or non-JSON body) yields no predictions for that batch; after **two consecutive
failed batches** or **90 s** (`ai.service.max-total-duration`) the remaining batches are not attempted, so a dead
service cannot stall an import. Only the failure kind is logged, never bodies or descriptions.

`GET {AI_SERVICE_URL}/health` →
```json
{ "status": "ok", "service": "finguard-ai", "version": "0.5.0", "capabilities": ["classify"],
  "classification": { "method": "rules", "version": "rules-1.0.0", "ml_model": false, "confidence_calibrated": false } }
```
`capabilities` lists exactly the implemented `/v1` endpoints (a test compares it with the OpenAPI paths).
