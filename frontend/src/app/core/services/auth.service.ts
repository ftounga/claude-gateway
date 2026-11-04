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
import { CompanionBridgeService, CompanionSession } from './companion-bridge.service';

const TOKEN_STORAGE_KEY = 'cg_token';
/** F-232 / SF-232-07 — la session du compagnon est renouvelée tant de temps avant son échéance. */
export const COMPANION_RENEW_BEFORE_MS = 5 * 60_000;

/**
 * Point d'accès unique à l'authentification côté SPA : appels API F-01 et gestion du JWT.
 *
 * Le token est conservé en localStorage (choix V1 documenté dans la mini-spec SF-01-07).
 * L'isolation des données reste garantie côté backend via le `user_id` porté par le JWT.
 *
 * F-232 / SF-232-07 — dans la WebView du compagnon Android, la session (portée `jarvis-compagnon`, 30 min) vient du
 * pont natif et ne vit **qu'en mémoire** : jamais dans le `localStorage`, renouvelée avant échéance. Hors compagnon,
 * rien ne change.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly companion = inject(CompanionBridgeService);
  private companionRenewTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly tokenSignal = signal<string | null>(this.readToken());

  constructor() {
    this.companion.onSessionDelivered((session) => this.adoptCompanionSession(session));
  }

  /** Vrai dans la WebView du compagnon Android (session du pont natif). */
  get inCompanion(): boolean {
    return this.companion.available;
  }

  /**
   * F-232 / SF-232-07 — demande une session au compagnon (ouverture, ou après un 401). Vrai si une session est en
   * place à la réponse. Hors compagnon : faux, sans rien faire.
   */
  async openCompanionSession(purpose: 'OPEN' | 'RENEW'): Promise<boolean> {
    if (!this.companion.available) {
      return false;
    }
    const session = await this.companion.session(purpose);
    return session !== null && this.tokenSignal() === session.token;
  }
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
    if (!this.companion.available) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
    }
    this.tokenSignal.set(token);
  }

  clearToken(): void {
    if (!this.companion.available) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    }
    this.clearCompanionRenewal();
    this.tokenSignal.set(null);
  }

  private readToken(): string | null {
    // Dans le compagnon, aucune session ne se lit du stockage : elle vient du pont natif.
    return this.companion.available ? null : localStorage.getItem(TOKEN_STORAGE_KEY);
  }

  private adoptCompanionSession(session: CompanionSession | null): void {
    if (!session) {
      return;
    }
    this.tokenSignal.set(session.token);
    this.clearCompanionRenewal();
    const expiresAt = Date.parse(session.expiresAt);
    if (Number.isFinite(expiresAt)) {
      const delay = Math.max(30_000, expiresAt - Date.now() - COMPANION_RENEW_BEFORE_MS);
      this.companionRenewTimer = setTimeout(() => {
        this.companionRenewTimer = null;
        void this.companion.session('RENEW');
      }, delay);
    }
  }

  private clearCompanionRenewal(): void {
    if (this.companionRenewTimer) {
      clearTimeout(this.companionRenewTimer);
      this.companionRenewTimer = null;
    }
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
