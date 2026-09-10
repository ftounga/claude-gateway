import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AccessGrantView, RedeemAccessCodeRequest } from '../models/access-code.models';

/**
 * Accès à l'API des codes d'accès à durée limitée (F-62). L'isolation est garantie côté backend par
 * le `user_id` porté par le JWT (ajouté par l'`authInterceptor`) : aucun identifiant d'utilisateur
 * n'est jamais envoyé depuis l'écran.
 */
@Injectable({ providedIn: 'root' })
export class AccessCodeService {
  private readonly http = inject(HttpClient);

  /** Accès offert en cours de l'utilisateur courant (toujours 200 : l'absence est un état normal). */
  getGrant(): Observable<AccessGrantView> {
    return this.http.get<AccessGrantView>('/api/access-code/grant');
  }

  /**
   * Consomme un code. Le serveur renormalise la saisie (casse, espaces) : on l'envoie telle quelle
   * plutôt que d'entretenir deux règles de normalisation qui divergeraient un jour.
   */
  redeem(code: string): Observable<AccessGrantView> {
    const body: RedeemAccessCodeRequest = { code };
    return this.http.post<AccessGrantView>('/api/access-code/redeem', body);
  }
}
