import type { components, operations } from "../../shared/api/generated/openapi";

export type TransactionType = components["schemas"]["TransactionType"];
export type TransactionSource = components["schemas"]["TransactionSource"];
/**
 * Who decided the category: nobody yet (`NONE`), a rule or a trained model (`RULE`, `MODEL`:
 * automatic suggestions), or the user (`USER`: confirmed, never overwritten by automation).
 */
export type CategorySource = components["schemas"]["CategorySource"];
/** What automatic categorisation achieved for an import or a re-run. */
export type ClassificationStatus = components["schemas"]["ClassificationStatus"];

/** Mirrors `TransactionResponse` in the backend (generated from OpenAPI contract). `amount` is always positive. */
export type Transaction = components["schemas"]["TransactionResponse"];
export type TransactionInput = components["schemas"]["TransactionRequest"];
export type CategoryUpdateInput = components["schemas"]["CategoryUpdateRequest"];
export type TransactionQuery = NonNullable<operations["Transaction_getAll"]["parameters"]["query"]>;

/** Result of asking the server to categorise the transactions that have no category. */
export type ReclassifyResult = components["schemas"]["ReclassifyResponse"];

export interface PagedTransactions {
  data: Transaction[];
  totalCount: number;
  page: number;
  pageSize: number;
  totalPages: number;
}
