// The key is unchanged from the original app so existing browser sessions stay signed in.
const TOKEN_KEY = "token";
const REFRESH_TOKEN_KEY = "refreshToken";

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);

export const setToken = (token: string): void => localStorage.setItem(TOKEN_KEY, token);

/** Refresh tokens are stored strictly in HttpOnly cookies, not in JavaScript-accessible storage (O-01). */
export const getRefreshToken = (): string | null => null;

export const setRefreshToken = (_token?: string): void => {
  void _token;
  // Deliberately no-op: refresh token is managed exclusively via HttpOnly cookie
  localStorage.removeItem(REFRESH_TOKEN_KEY);
};

export const setTokens = (accessToken: string, _refreshToken?: string): void => {
  void _refreshToken;
  setToken(accessToken);
  // Clean up any legacy refresh token from localStorage to eliminate XSS exposure
  localStorage.removeItem(REFRESH_TOKEN_KEY);
};

export const clearToken = (): void => {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
};

/** Reads the `exp` claim (seconds since epoch) from a JWT without verifying it. The server stays the authority. */
function expiryOf(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const exp = (JSON.parse(json) as { exp?: unknown }).exp;
    return typeof exp === "number" ? exp : null;
  } catch {
    return null;
  }
}

/** True when a token is stored and has not passed its expiry. A server 401 still wins over this check. */
export function isAuthenticated(): boolean {
  const token = getToken();
  if (!token) return false;
  const exp = expiryOf(token);
  return exp === null || exp * 1000 > Date.now();
}
