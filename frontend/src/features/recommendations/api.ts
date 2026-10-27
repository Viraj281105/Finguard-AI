import { apiClient } from "../../shared/api/client";
import type { Recommendations } from "./types";

/** Calculated on demand by the backend from the caller's own records; nothing is stored or cached. */
export const recommendationsAPI = {
  get: () => apiClient.get<Recommendations>("/recommendations").then((r) => r.data),
};
