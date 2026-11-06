import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

import { AuthService } from '../../core/services/auth.service';
import { OnboardingService } from '../../core/services/onboarding.service';

/** SF-01-10 — repère de la reprise automatique : une seule par minute, jamais de boucle. */
export const OAUTH_RETRY_KEY = 'cg.oauth.retry';
const RETRY_WINDOW_MS = 60_000;

/**
 * Réception du retour OAuth Google : lit le JWT dans le fragment d'URL (`#token=...`),
 * le stocke et redirige vers le profil. En cas d'`#error=...`, affiche un message.
 *
 * SF-01-10 — `authorization_request_not_found` : le retour de Google a atterri dans une autre application que celle du
 * départ (sur Android, l'application installée capte le lien) ; le départ est relancé une fois, ici même, et Google,
 * déjà choisi, revient aussitôt dans la bonne application.
 */
@Component({
  selector: 'app-oauth-callback',
  imports: [RouterLink, MatCardModule, MatIconModule, MatButtonModule],
  templateUrl: './oauth-callback.component.html',
})
export class OauthCallbackComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);

  readonly failed = signal(false);

  ngOnInit(): void {
    const fragment = window.location.hash.startsWith('#')
      ? window.location.hash.substring(1)
      : window.location.hash;
    const params = new URLSearchParams(fragment);
    const token = params.get('token');

    if (token) {
      forgetRetry();
      this.authService.storeToken(token);
      void this.router.navigate([this.onboarding.postLoginPath()]);
      return;
    }
    if (params.get('error') === 'authorization_request_not_found' && claimRetry(Date.now())) {
      this.restart(this.authService.googleLoginUrl);
      return;
    }
    this.failed.set(true);
  }

  /** Relance le départ Google (navigation pleine page) ; isolé pour les tests. */
  protected restart(url: string): void {
    window.location.assign(url);
  }
}

/** Vrai si une reprise est permise maintenant (aucune dans la dernière minute) ; la note. */
export function claimRetry(now: number): boolean {
  try {
    const last = Number(sessionStorage.getItem(OAUTH_RETRY_KEY));
    if (last && now - last < RETRY_WINDOW_MS) {
      return false;
    }
    sessionStorage.setItem(OAUTH_RETRY_KEY, String(now));
    return true;
  } catch {
    return false;
  }
}

function forgetRetry(): void {
  try {
    sessionStorage.removeItem(OAUTH_RETRY_KEY);
  } catch {
    // Stockage indisponible : rien à oublier.
  }
}
