/**
 * Common shared types for API errors, identifiers, and utility structures.
 */

export interface ApiErrorResponse {
  timestamp?: string;
  status: number;
  error?: string;
  message: string;
  path?: string;
  fieldErrors?: Record<string, string>;
}

export interface Identifiable {
  id: string;
}
