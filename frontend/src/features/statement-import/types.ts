import type { ClassificationStatus } from "../transactions/types";

export type JobStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface RowError {
  rowNumber: number;
  errorMessage: string;
  snippet: string;
}

/** Mirrors `UploadStatusResponse` in the backend (see docs/API.md). */
export interface UploadStatus {
  jobId: string;
  status: JobStatus;
  bankName: string;
  originalFilename: string;
  totalRows: number;
  rowsProcessed: number;
  duplicateRows: number;
  rejectedRows: number;
  skippedRows: number;
  rowErrors?: RowError[];
  /** What automatic categorisation did for this import; the import itself succeeds either way. */
  classificationStatus?: ClassificationStatus;
  /** Imported transactions that received an automatic category. */
  classifiedRows?: number;
  closingBalance?: number | null;
  statementFrom?: string | null;
  statementTo?: string | null;
  errorMessage: string | null;
  createdAt: string | null;
  completedAt: string | null;
}
