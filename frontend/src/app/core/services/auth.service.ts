import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import {
  AuthResponse,
  LoginRequest,
  MessageResponse,
  RegisterRequest,
  UpdateProfileRequest,
  UserProfile,
  VerifyEmailResponse,
} from '../models/auth.models';

const TOKEN_STORAGE_KEY = 'cg_token';

/**
 * Point d'accès unique à l'authentification côté SPA : appels API F-01 et gestion du JWT.
 *
 * Le token est conservé en localStorage (choix V1 documenté dans la mini-spec SF-01-07).
 * L'isolation des données reste garantie côté backend via le `user_id` porté par le JWT.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly tokenSignal = signal<string | null>(this.readToken());
  /** Vrai si un JWT est présent (validité réelle vérifiée côté backend). */
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  /**
   * Rôle de l'utilisateur courant, lu depuis le claim `role` du JWT (affichage uniquement — l'accès
   * réel est toujours contrôlé côté backend). `null` si absent de token.
   */
  readonly role = computed(() => this.decodeRole(this.tokenSignal()));
  /** Vrai si l'utilisateur courant est administrateur (pour l'affichage du lien Admin). */
  readonly isAdmin = computed(() => this.role() === 'ADMIN');

  /** URL de démarrage du flux OAuth Google (redirection pleine page). */
  readonly googleLoginUrl = '/api/oauth2/authorization/google';

  register(body: RegisterRequest): Observable<UserProfile> {
    return this.http.post<UserProfile>('/api/auth/register', body);
  }

  login(body: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/login', body)
      .pipe(tap((response) => this.storeToken(response.accessToken)));
  }

  verifyEmail(token: string): Observable<VerifyEmailResponse> {
    return this.http.get<VerifyEmailResponse>('/api/auth/verify', { params: { token } });
  }

  forgotPassword(email: string): Observable<MessageResponse> {
    return this.http.post<MessageResponse>('/api/auth/password/forgot', { email });
  }

  resetPassword(token: string, password: string): Observable<MessageResponse> {
    return this.http.post<MessageResponse>('/api/auth/password/reset', { token, password });
  }

  me(): Observable<UserProfile> {
    return this.http.get<UserProfile>('/api/me');
  }

  updateProfile(body: UpdateProfileRequest): Observable<UserProfile> {
    return this.http.put<UserProfile>('/api/me', body);
  }

  /** Déconnexion de la session courante : purge le token après l'appel serveur. */
  logout(): Observable<MessageResponse> {
    return this.http.post<MessageResponse>('/api/me/logout', {}).pipe(tap(() => this.clearToken()));
  }

  /** Déconnexion de toutes les sessions (invalide les tokens antérieurs côté serveur). */
  logoutAll(): Observable<MessageResponse> {
    return this.http
      .post<MessageResponse>('/api/me/logout-all', {})
      .pipe(tap(() => this.clearToken()));
  }

  /** JWT courant (ou null). */
  token(): string | null {
    return this.tokenSignal();
  }

  storeToken(token: string): void {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    this.tokenSignal.set(token);
  }

  clearToken(): void {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    this.tokenSignal.set(null);
  }

  private readToken(): string | null {
    return localStorage.getItem(TOKEN_STORAGE_KEY);
  }

  /** Décode le claim `role` du payload JWT (sans vérifier la signature — usage affichage uniquement). */
  private decodeRole(token: string | null): string | null {
    if (!token) {
      return null;
    }
    try {
      const payload = token.split('.')[1];
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      const claims = JSON.parse(json) as { role?: string };
      return claims.role ?? null;
    } catch {
      return null;
    }
  }
}
