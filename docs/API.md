# HTTP API

Base URL (development): `http://localhost:8080`. All paths are under `/api`. JSON in, JSON out, except the file
upload (`multipart/form-data`).

**Status:** every endpoint listed here exists and is exercised by an automated test unless a row says otherwise
(tests: `AuthApiTest`, `OwnershipApiTest`, `LedgerApiTest`, `CategoryApiTest`, `ImportApiTest`,
`ImportClassificationTest`). Nothing outside this list exists — in particular there are **no** risk, forecast,
scenario, recommendation, analytics or profile endpoints yet (see [ROADMAP.md](ROADMAP.md)).

There is no API version prefix. See [DECISIONS.md](DECISIONS.md) D-07.

## Authentication

- `POST /api/auth/**` is public. **Every other endpoint requires** `Authorization: Bearer <access token>`.
- Tokens are signed JWTs (HMAC). Claims: `sub` (email), `typ` (`access` or `refresh`), `jti`, `iat`, `exp`.
- Lifetimes: access **15 minutes** (900,000 ms), refresh **7 days** (604,800,000 ms) (configurable: `JWT_EXPIRATION_MS`, `JWT_REFRESH_EXPIRATION_MS`).
- A refresh token is rejected as a bearer token; an access token is rejected by `/auth/refresh`.
- The user is always identified from the token. No endpoint accepts a user id.
- **Refresh Token Rotation & Revocation**: Refresh tokens are single-use. When `/api/auth/refresh` is called, a new refresh token is issued and the previous token is marked revoked and replaced. If an already-replaced refresh token is re-submitted (token reuse attack), all active sessions for that user account are revoked immediately (D-03b).
- **Cookies**: Auth endpoints set an `HttpOnly`, `SameSite=Strict` cookie for `refreshToken` scoped to `Path=/api/auth`. The refresh endpoint accepts the token from either the JSON request body or the cookie.
- **Brute-Force Rate Limiting**: An in-memory sliding window tracks failed authentication attempts by IP address and normalized email. 5 failed login attempts in 60 seconds throttles subsequent attempts with HTTP `429 Too Many Requests` and a `Retry-After: 60` response header (D-04). A successful login clears the failed attempt count.
- **Email Normalization**: Emails are trimmed and converted to lowercase on registration, login, and token generation (D-05).

### `POST /api/auth/register`

```json
{ "name": "Asha", "email": "asha@example.com", "password": "securepassword123" }
```
`name` and `email` required; `email` must be valid (normalized to lowercase); `password` at least 10 characters (D-02).

`200` →
```json
{ "accessToken": "eyJ…", "refreshToken": "eyJ…", "email": "asha@example.com", "name": "Asha" }
```
Sets `Set-Cookie: refreshToken=...; HttpOnly; SameSite=Strict; Path=/api/auth; Max-Age=604800`.
`409` `Email already registered` · `400` validation (see Errors).

### `POST /api/auth/login`

```json
{ "email": "asha@example.com", "password": "securepassword123" }
```
`200` same body as register and sets the `refreshToken` cookie · `401` `Invalid email or password` (identical for unknown email and wrong password) · `429` `Too many login attempts. Please try again later.` with `Retry-After: 60`.

### `POST /api/auth/refresh`

```json
{ "refreshToken": "eyJ…" }
```
*(Body is optional if `refreshToken` cookie is present in request)*

`200` new `accessToken` and `refreshToken` (same body shape and updated `Set-Cookie`) · `401` `Invalid refresh token` or `Refresh token expired or revoked`.
Replay of an already-rotated token triggers token reuse detection and revokes all refresh tokens for that user account.

### `POST /api/auth/logout`

```json
{ "refreshToken": "eyJ…" }
```
*(Body is optional if `refreshToken` cookie is present in request)*

`200` →
```json
{ "status": 200, "message": "Logged out successfully" }
```
Clears the `refreshToken` cookie with `Max-Age=0` and invalidates the session in the database.

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
| `POST /api/transactions` | `200` created object |
| `GET /api/transactions` | `200` JSON array of the caller's transactions, sorted deterministically by `date DESC, createdAt DESC`. Supports query parameters and pagination headers. |
| `GET /api/transactions/{id}` | `200` object · `404` if missing **or owned by someone else** |
| `PUT /api/transactions/{id}` | `200` updated object (full replacement of the fields, preserves existing `date` if omitted; see `category` rules above — **0.5.0 change:** an absent `category` now keeps the current one instead of clearing it) · `400` unknown category · `404` |
| `PATCH /api/transactions/{id}/category` | Records the user's own category. Body `{"category": "GROCERIES"}` (id or legacy label; blank/missing/unknown → `400`). `200` updated object with `categorySource: "USER"`, confidence/rule/version cleared · `404` missing or not owned |
| `POST /api/transactions/reclassify` | Asks the AI service again for the caller's transactions with no category (`categorySource = NONE`), newest first. Query `limit` (default 200, max 500). Never touches a `USER` category and only the caller's rows. `200` `{ "status", "attempted", "categorized", "needsReview", "stillUncategorized" }`; `status` is `COMPLETED`, `PARTIAL`, `UNAVAILABLE` (AI service down — nothing changed, still `200`) or `NOT_ATTEMPTED` (nothing to do) |
| `DELETE /api/transactions/{id}` | `204` · `404` |

### Query Parameters for `GET /api/transactions`

| Parameter | Type | Description |
|---|---|---|
| `page` | integer | Page index (0-based, default 0 when paged). Must be `>= 0`. |
| `size` | integer | Page size (1 to 100). When omitted, returns all matching rows (backward-compatible). |
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
non-empty, at most **5 MB** (`spring.servlet.multipart.max-file-size`), and at most **10,000 rows**.
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
