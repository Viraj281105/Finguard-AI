/** Body returned by POST /api/auth/login, /register and /refresh. */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  email: string;
  name: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

export interface RegisterInput extends LoginInput {
  name: string;
}
