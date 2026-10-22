import { apiClient } from "../../shared/api/client";
import type { Loan, LoanInput } from "./types";

export const loanAPI = {
  getAll: () => apiClient.get<Loan[]>("/loans").then((r) => r.data),

  getById: (id: string) => apiClient.get<Loan>(`/loans/${id}`).then((r) => r.data),

  create: (input: LoanInput) => apiClient.post<Loan>("/loans", input).then((r) => r.data),

  update: (id: string, input: LoanInput) =>
    apiClient.put<Loan>(`/loans/${id}`, input).then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/loans/${id}`).then(() => undefined),
};
