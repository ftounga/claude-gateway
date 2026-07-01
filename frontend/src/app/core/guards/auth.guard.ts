import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';

/**
 * Protège les routes nécessitant une authentification : redirige vers `/login` en l'absence
 * de JWT. La validité réelle du token est vérifiée côté backend (401 → purge via l'interceptor).
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};
