import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../services/auth.service';
import { CompanionBridgeService } from '../services/companion-bridge.service';

/** Endpoints publics d'auth : ne pas y déclencher la redirection 401 (échec attendu = feedback UI). */
const PUBLIC_AUTH_PATHS = ['/api/auth/'];

/**
 * Injecte le JWT en `Authorization: Bearer` sur les requêtes API et gère les 401 :
 * sur une route protégée, purge le token et redirige vers `/login`.
 *
 * F-232 / SF-232-07 — dans le compagnon Android, un 401 redemande une session au pont natif (jamais `/login`) ; une
 * session refusée ferme l'écran du compagnon. Un 403 `jarvis_companion_scope` reste une erreur ordinaire.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const bridge = inject(CompanionBridgeService);

  const token = authService.token();
  const authorized =
    token && req.url.startsWith('/api')
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      const isPublicAuthCall = PUBLIC_AUTH_PATHS.some((path) => req.url.startsWith(path));
      if (error.status === 401 && !isPublicAuthCall && authService.inCompanion) {
        void authService.openCompanionSession('RENEW').then((ok) => {
          if (!ok) {
            authService.clearToken();
            bridge.close();
          }
        });
      } else if (error.status === 401 && !isPublicAuthCall) {
        authService.clearToken();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
