import type { components } from "../../shared/api/generated/openapi";

export type JobStatus = components["schemas"]["JobStatus"];
export type RowError = components["schemas"]["RowErrorDto"];

/** Mirrors `UploadStatusResponse` in the backend (generated from OpenAPI contract). */
export type UploadStatus = components["schemas"]["UploadStatusResponse"];
