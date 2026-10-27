export const MAX_FILE_BYTES = 5 * 1024 * 1024; // keep in sync with spring.servlet.multipart.max-file-size
export const POLL_INTERVAL_MS = 2000;
export const MAX_POLL_ATTEMPTS = 60; // 120 seconds maximum polling before timeout
/** Pause after completion so the user can read the success message before the dashboard refreshes. */
export const REFRESH_DELAY_MS = 3000;
