import { apiClient } from "../../shared/api/client";
import type { Investment, InvestmentInput } from "./types";

export const investmentAPI = {
  getAll: () => apiClient.get<Investment[]>("/investments").then((r) => r.data),

  getById: (id: string) =>
    apiClient.get<Investment>(`/investments/${id}`).then((r) => r.data),

  create: (input: InvestmentInput) =>
    apiClient.post<Investment>("/investments", input).then((r) => r.data),

  update: (id: string, input: InvestmentInput) =>
    apiClient.put<Investment>(`/investments/${id}`, input).then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/investments/${id}`).then(() => undefined),
};
