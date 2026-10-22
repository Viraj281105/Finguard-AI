/**
 * The transaction category taxonomy. Identifiers must match `contracts/categories.json`, which is
 * shared with the AI service, the Java backend and the database; `categories.test.ts` fails if they drift.
 */

export type CategoryFlow = "INCOME" | "EXPENSE" | "NON_SPENDING" | "REFUND" | "UNKNOWN";
export type FlowDirection = "INCOME" | "EXPENSE";

export interface CategoryInfo {
  id: string;
  label: string;
  flow: CategoryFlow;
  /** Directions the category suits. */
  types: readonly FlowDirection[];
}

export const CATEGORIES: readonly CategoryInfo[] = [
  { id: "INCOME", label: "Income", flow: "INCOME", types: ["INCOME"] },
  { id: "GROCERIES", label: "Groceries", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "DINING", label: "Dining", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "TRANSPORT", label: "Transport", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "HOUSING", label: "Housing", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "UTILITIES", label: "Utilities", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "HEALTHCARE", label: "Healthcare", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "INSURANCE", label: "Insurance", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "SHOPPING", label: "Shopping", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "SUBSCRIPTIONS", label: "Subscriptions", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "EDUCATION", label: "Education", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "ENTERTAINMENT", label: "Entertainment", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "FEES", label: "Fees & charges", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "LOAN_PAYMENTS", label: "Loan payments", flow: "EXPENSE", types: ["EXPENSE"] },
  { id: "INVESTMENTS", label: "Investments", flow: "NON_SPENDING", types: ["INCOME", "EXPENSE"] },
  { id: "TRANSFERS", label: "Transfers", flow: "NON_SPENDING", types: ["INCOME", "EXPENSE"] },
  { id: "CARD_PAYMENTS", label: "Credit card payments", flow: "NON_SPENDING", types: ["EXPENSE"] },
  { id: "REFUNDS", label: "Refunds & reversals", flow: "REFUND", types: ["INCOME"] },
  { id: "UNCATEGORIZED", label: "Uncategorized", flow: "UNKNOWN", types: ["INCOME", "EXPENSE"] },
];

const BY_ID: ReadonlyMap<string, CategoryInfo> = new Map(CATEGORIES.map((c) => [c.id, c]));

export function categoryInfo(id: string | null | undefined): CategoryInfo | undefined {
  return id ? BY_ID.get(id) : undefined;
}

/** Display name of a category id; unknown ids (e.g. from a newer server) are shown as they are. */
export function categoryLabel(id: string | null | undefined): string {
  if (!id) return "Uncategorized";
  return BY_ID.get(id)?.label ?? id;
}

/** Categories a person can pick for a transaction of the given direction. */
export function categoriesForType(type: FlowDirection): readonly CategoryInfo[] {
  return CATEGORIES.filter((c) => c.types.includes(type));
}

/**
 * Whether a transaction counts toward "income" / "expenses" totals. Transfers, credit-card bill
 * payments, investment movements and refunds are money moving, not earning or spending, so they
 * are excluded; uncategorised transactions stay in until a person says otherwise.
 */
export function countsTowardTotals(category: string | null | undefined): boolean {
  const flow = categoryInfo(category)?.flow;
  return flow !== "NON_SPENDING" && flow !== "REFUND";
}
