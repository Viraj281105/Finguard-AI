import axios, { isAxiosError, type InternalAxiosRequestConfig } from "axios";
import { clearToken, getToken, isAuthenticated, setTokens } from "../auth/tokenStorage";

/** Set VITE_API_BASE_URL (see .env.example) to point the app at a different backend. */
export const API_BASE_URL: string =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
});

function randomHex(bytes: number): string {
  const buf = new Uint8Array(bytes);
  if (typeof globalThis.crypto?.getRandomValues === "function") {
    globalThis.crypto.getRandomValues(buf);
  } else {
    for (let i = 0; i < bytes; i++) {
      buf[i] = Math.floor(Math.random() * 256);
    }
  }
  let allZero = true;
  for (let i = 0; i < bytes; i++) {
    if (buf[i] !== 0) {
      allZero = false;
      break;
    }
  }
  if (allZero && bytes > 0) {
    buf[bytes - 1] = 1;
  }
  return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Generates a fresh W3C Trace Context `traceparent` header (`00-<32-hex>-<16-hex>-01`). */
export function generateTraceparent(): string {
  return `00-${randomHex(16)}-${randomHex(8)}-01`;
}

// Attach the JWT and W3C traceparent to every request.
apiClient.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (!config.headers.traceparent) {
    config.headers.traceparent = generateTraceparent();
  }
  return config;
});

function redirectToLogin(): void {
  if (window.location.pathname !== "/login") {
    window.location.assign("/login");
  }
}

let onSessionExpired: () => void = redirectToLogin;

/** Replaces the action taken when the server rejects the session (tests use this; the app keeps the default). */
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler;
}

interface CustomRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

let isRefreshing = false;
let refreshPromise: Promise<string | null> | null = null;

async function performRefreshCall(): Promise<string | null> {
  try {
    const res = await axios.post<{ accessToken: string; refreshToken?: string }>(
      `${API_BASE_URL}/auth/refresh`,
      {},
      { withCredentials: true }
    );
    const newAccessToken = res.data.accessToken;
    setTokens(newAccessToken, res.data.refreshToken);
    return newAccessToken;
  } catch {
    clearToken();
    return null;
  }
}

async function executeRefresh(): Promise<string | null> {
  const tokenBefore = getToken();
  // Coordinate across multiple browser tabs using Web Locks when supported (O-03)
  if (typeof navigator !== "undefined" && navigator?.locks?.request) {
    return navigator.locks.request("finguard_auth_refresh", async () => {
      const currentToken = getToken();
      if (currentToken && currentToken !== tokenBefore && isAuthenticated()) {
        return currentToken;
      }
      return performRefreshCall();
    });
  }
  return performRefreshCall();
}

// Interceptor handles 401 on protected routes via silent refresh with rotation.
// Parallel 401s join a single in-flight refresh request. A failed refresh logs out.
apiClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!isAxiosError(error) || !error.config) {
      return Promise.reject(error);
    }

    const originalRequest = error.config as CustomRequestConfig;
    const url = originalRequest.url ?? "";
    const isAuthEndpoint = url.startsWith("/auth/") || url.startsWith("auth/");

    if (error.response?.status === 401 && !isAuthEndpoint) {
      if (originalRequest._retry) {
        clearToken();
        onSessionExpired();
        return Promise.reject(error);
      }

      originalRequest._retry = true;

      if (!isRefreshing) {
        isRefreshing = true;
        refreshPromise = executeRefresh().finally(() => {
          isRefreshing = false;
          refreshPromise = null;
        });
      }

      const newAccessToken = await refreshPromise;
      if (newAccessToken) {
        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
        return apiClient(originalRequest);
      } else {
        clearToken();
        onSessionExpired();
        return Promise.reject(error);
      }
    }

    return Promise.reject(error);
  },
);

interface ApiErrorBody {
  message?: string;
  fieldErrors?: Record<string, string>;
}

/**
 * The most useful message for a failed request: the first field error, else the server's message,
 * else the supplied fallback. Backend error bodies are described in docs/API.md.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError<ApiErrorBody>(error)) {
    const body = error.response?.data;
    const firstField = body?.fieldErrors ? Object.values(body.fieldErrors)[0] : undefined;
    return firstField ?? body?.message ?? fallback;
  }
  return fallback;
}
