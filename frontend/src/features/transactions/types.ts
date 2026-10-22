export type TransactionType = "INCOME" | "EXPENSE";
export type TransactionSource = "MANUAL" | "IMPORT";
/**
 * Who decided the category: nobody yet (`NONE`), a rule or a trained model (`RULE`, `MODEL`:
 * automatic suggestions), or the user (`USER`: confirmed, never overwritten by automation).
 */
export type CategorySource = "NONE" | "RULE" | "MODEL" | "USER";
/** What automatic categorisation achieved for an import or a re-run. */
export type ClassificationStatus = "NOT_ATTEMPTED" | "COMPLETED" | "PARTIAL" | "UNAVAILABLE";

/** Mirrors `TransactionResponse` in the backend (see docs/API.md). `amount` is always positive. */
export interface Transaction {
  id: string;
  title: string;
  amount: number;
  type: TransactionType;
  /** Canonical category id (see shared/categories); null until classified or chosen. */
  category: string | null;
  note: string | null;
  /** Dedicated financial transaction date (YYYY-MM-DD). */
  date: string;
  source: TransactionSource;
  categorySource?: CategorySource | null;
  /** Raw confidence of an automatic category. Show it as a percentage only if `categoryConfidenceCalibrated`. */
  categoryConfidence?: number | null;
  categoryConfidenceCalibrated?: boolean;
  /** The automatic category rests on weak or conflicting evidence. */
  categoryNeedsReview?: boolean;
  categoryModelVersion?: string | null;
  categoryRule?: string | null;
  createdAt: string;
}

export interface TransactionInput {
  title: string;
  amount: number;
  type: TransactionType;
  category?: string;
  note?: string;
  /** Financial date (YYYY-MM-DD). Defaults to today if omitted. */
  date?: string;
}

export interface TransactionQuery {
  page?: number;
  size?: number;
  from?: string;
  to?: string;
  type?: TransactionType;
  /** A category id; `UNCATEGORIZED` lists transactions without a category. */
  category?: string;
  /** Only automatic categories flagged for review. */
  needsReview?: boolean;
}

/** Result of asking the server to categorise the transactions that have no category. */
export interface ReclassifyResult {
  status: ClassificationStatus;
  attempted: number;
  categorized: number;
  needsReview: number;
  stillUncategorized: number;
}

export interface PagedTransactions {
  data: Transaction[];
  totalCount: number;
  page: number;
  pageSize: number;
  totalPages: number;
}
