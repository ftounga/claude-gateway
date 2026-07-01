/** Contrats DTO de l'API d'authentification (F-01). Figés par le backend. */

export type AuthProvider = 'LOCAL' | 'GOOGLE';
export type UserRole = 'USER' | 'ADMIN';

/** Vue publique du compte courant (GET /api/me, register, login.user). */
export interface UserProfile {
  id: string;
  email: string;
  emailVerified: boolean;
  provider: AuthProvider;
  role: UserRole;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  user: UserProfile;
}

export interface VerifyEmailResponse {
  verified: boolean;
  email: string;
}

export interface MessageResponse {
  message: string;
}

export interface UpdateProfileRequest {
  email: string;
}

/** Corps d'erreur homogène renvoyé par l'API. */
export interface ApiError {
  error: string;
  message: string;
}
