import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AdminUsageView } from './admin-usage.models';

/**
 * Accès à la consommation par utilisateur (F-61 / SF-61-03). L'autorisation réelle (rôle ADMIN)
 * est appliquée côté backend, qui répond `403` à tout autre appelant.
 */
@Injectable({ providedIn: 'root' })
export class AdminUsageService {
  private readonly http = inject(HttpClient);

  /** Consommation de la plateforme sur les `months` derniers mois, mois courant compris. */
  getUsage(months: number): Observable<AdminUsageView> {
    const now = new Date();
    const to = firstOfMonth(now.getUTCFullYear(), now.getUTCMonth());
    const from = firstOfMonth(now.getUTCFullYear(), now.getUTCMonth() - (months - 1));
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<AdminUsageView>('/api/admin/usage', { params });
  }
}

/** Premier jour du mois demandé, en ISO — `monthIndex` peut être négatif (année précédente). */
function firstOfMonth(year: number, monthIndex: number): string {
  const date = new Date(Date.UTC(year, monthIndex, 1));
  return date.toISOString().slice(0, 10);
}
