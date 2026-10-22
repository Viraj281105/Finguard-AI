import { apiClient } from "../../shared/api/client";
import type {
  PagedTransactions, ReclassifyResult, Transaction, TransactionInput, TransactionQuery,
} from "./types";

export const transactionAPI = {
  getAll: (query?: TransactionQuery) =>
    apiClient.get<Transaction[]>("/transactions", { params: query }).then((r) => r.data),

  getPage: (query?: TransactionQuery): Promise<PagedTransactions> =>
    apiClient.get<Transaction[]>("/transactions", { params: query }).then((r) => {
      const getHeader = (name: string, fallback: string): string => {
        const val = r.headers[name] ?? r.headers[name.toLowerCase()];
        return typeof val === "string" ? val : fallback;
      };
      return {
        data: r.data,
        totalCount: parseInt(getHeader("x-total-count", `${r.data.length}`), 10),
        page: parseInt(getHeader("x-page", "0"), 10),
        pageSize: parseInt(getHeader("x-page-size", `${r.data.length}`), 10),
        totalPages: parseInt(getHeader("x-total-pages", "1"), 10),
      };
    }),

  getById: (id: string) =>
    apiClient.get<Transaction>(`/transactions/${id}`).then((r) => r.data),

  create: (input: TransactionInput) =>
    apiClient.post<Transaction>("/transactions", input).then((r) => r.data),

  update: (id: string, input: TransactionInput) =>
    apiClient.put<Transaction>(`/transactions/${id}`, input).then((r) => r.data),

  /** Records the user's own category. It is authoritative: automation never replaces it. */
  updateCategory: (id: string, category: string) =>
    apiClient.patch<Transaction>(`/transactions/${id}/category`, { category }).then((r) => r.data),

  /** Asks the server to categorise the user's transactions that have no category yet. */
  reclassify: () =>
    apiClient.post<ReclassifyResult>("/transactions/reclassify").then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/transactions/${id}`).then(() => undefined),
};

