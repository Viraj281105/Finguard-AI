import { apiClient } from "../../shared/api/client";
import { clearToken, getRefreshToken, setTokens } from "../../shared/auth/tokenStorage";
import type { AuthResponse, LoginInput, RegisterInput } from "./types";

export const authAPI = {
  login: (input: LoginInput) =>
    apiClient.post<AuthResponse>("/auth/login", input).then((r) => {
      setTokens(r.data.accessToken, r.data.refreshToken);
      return r.data;
    }),

  register: (input: RegisterInput) =>
    apiClient.post<AuthResponse>("/auth/register", input).then((r) => {
      setTokens(r.data.accessToken, r.data.refreshToken);
      return r.data;
    }),

  refresh: (token?: string) => {
    const refreshToken = token ?? getRefreshToken();
    return apiClient
      .post<AuthResponse>("/auth/refresh", { refreshToken })
      .then((r) => {
        setTokens(r.data.accessToken, r.data.refreshToken);
        return r.data;
      });
  },

  logout: () => {
    const refreshToken = getRefreshToken();
    return apiClient
      .post("/auth/logout", { refreshToken })
      .catch(() => {})
      .finally(() => {
        clearToken();
      });
  },
};
