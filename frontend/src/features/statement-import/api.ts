import { apiClient } from "../../shared/api/client";
import type { UploadStatus } from "./types";

export const uploadAPI = {
  /** Sends the CSV as multipart/form-data. The server answers 202 immediately; processing continues in the background. */
  uploadCsv: (file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return apiClient
      .post<UploadStatus>("/upload/csv", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },

  /** Current state of an import job; poll until it is COMPLETED or FAILED. */
  getStatus: (jobId: string) =>
    apiClient.get<UploadStatus>(`/upload/status/${jobId}`).then((r) => r.data),

  /** History of all statement import jobs for the authenticated user. */
  getJobs: () =>
    apiClient.get<UploadStatus[]>("/upload/jobs").then((r) => r.data),
};
