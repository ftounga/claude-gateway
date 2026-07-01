import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../services/auth.service';

/** Endpoints publics d'auth : ne pas y déclencher la redirection 401 (échec attendu = feedback UI). */
const PUBLIC_AUTH_PATHS = ['/api/auth/'];

/**
 * Injecte le JWT en `Authorization: Bearer` sur les requêtes API et gère les 401 :
 * sur une route protégée, purge le token et redirige vers `/login`.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const token = authService.token();
  const authorized =
    token && req.url.startsWith('/api')
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      const isPublicAuthCall = PUBLIC_AUTH_PATHS.some((path) => req.url.startsWith(path));
      if (error.status === 401 && !isPublicAuthCall) {
        authService.clearToken();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
