import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { UsageByClientView } from '../models/usage-by-client.models';

/**
 * Accès à la consommation par client (F-61 / SF-61-02, `GET /api/usage/by-client`).
 *
 * <p>Aucun identifiant utilisateur n'est transmis : l'isolation est garantie côté backend par le
 * `user_id` porté par le JWT (ajouté par l'`authInterceptor`).</p>
 */
@Injectable({ providedIn: 'root' })
export class UsageByClientService {
  private readonly http = inject(HttpClient);

  /**
   * Consommation par client de l'utilisateur courant sur les `months` derniers mois, mois courant
   * compris. Les bornes sont calculées ici et renvoyées par le backend, qui les normalise au
   * premier du mois — le mois est le grain réel de la donnée.
   */
  getByClient(months: number): Observable<UsageByClientView> {
    const now = new Date();
    const to = firstOfMonth(now.getUTCFullYear(), now.getUTCMonth());
    const from = firstOfMonth(now.getUTCFullYear(), now.getUTCMonth() - (months - 1));
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<UsageByClientView>('/api/usage/by-client', { params });
  }
}

/** Premier jour du mois demandé, en ISO — `monthIndex` peut être négatif (année précédente). */
function firstOfMonth(year: number, monthIndex: number): string {
  const date = new Date(Date.UTC(year, monthIndex, 1));
  return date.toISOString().slice(0, 10);
}
