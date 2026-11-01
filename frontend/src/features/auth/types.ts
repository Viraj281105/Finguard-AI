import type { components } from "../../shared/api/generated/openapi";

/** Body returned by POST /api/auth/login, /register and /refresh (generated from OpenAPI contract). */
export type AuthResponse = components["schemas"]["AuthResponse"];
export type LoginInput = components["schemas"]["LoginRequest"];
export type RegisterInput = components["schemas"]["RegisterRequest"];
export type RefreshInput = components["schemas"]["RefreshRequest"];
export type LogoutInput = components["schemas"]["LogoutRequest"];
