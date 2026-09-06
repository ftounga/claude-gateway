import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { QuotaAlertView } from '../models/quota-alert.models';

/**
 * Accès à l'API d'alerte de consommation F-42. Le frontend ne communique qu'avec Claude Gateway
 * (`/api/...`). L'isolation des données est garantie côté backend via le `user_id` porté par le JWT
 * (ajouté par l'`authInterceptor`) : aucun identifiant utilisateur n'est transmis par le client.
 */
@Injectable({ providedIn: 'root' })
export class QuotaAlertService {
  private readonly http = inject(HttpClient);

  /** Alerte de consommation de l'utilisateur courant pour la période en cours. */
  getAlert(): Observable<QuotaAlertView> {
    return this.http.get<QuotaAlertView>('/api/usage/alert');
  }

  /**
   * Écarte l'alerte de la période courante : elle ne sera plus présentée avant le mois suivant,
   * même si la consommation continue de monter.
   */
  dismissAlert(): Observable<void> {
    return this.http.post<void>('/api/usage/alert/dismiss', {});
  }
}
