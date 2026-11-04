import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';
import { CompanionBridgeService } from '../services/companion-bridge.service';

/**
 * Protège les routes nécessitant une authentification : redirige vers `/login` en l'absence
 * de JWT. La validité réelle du token est vérifiée côté backend (401 → purge via l'interceptor).
 *
 * F-232 / SF-232-07 — dans la WebView du compagnon Android, la session est demandée au pont natif ; sans session (jeton
 * du compagnon refusé), l'écran du compagnon se ferme plutôt que d'afficher une connexion qu'il ne peut pas faire.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }
  if (authService.inCompanion) {
    const bridge = inject(CompanionBridgeService);
    return authService.openCompanionSession('OPEN').then((ok) => {
      if (!ok) {
        bridge.close();
      }
      return ok;
    });
  }
  return router.createUrlTree(['/login']);
};
