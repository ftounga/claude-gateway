import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DiagnosticReport } from './admin-diagnostic.models';

/**
 * **Le diagnostic du produit** (F-156 / SF-156-05) — administration.
 *
 * <p>Il se lance **à la demande** : le lancer à chaque fermeture de session reviendrait à
 * ré-analyser les mêmes données pour la même conclusion.</p>
 */
@Injectable({ providedIn: 'root' })
export class AdminDiagnosticService {
  private readonly http = inject(HttpClient);

  /** Lance le diagnostic sur les `days` derniers jours. Aucun jeton n'est consommé. */
  run(days: number): Observable<DiagnosticReport> {
    const params = new HttpParams().set('days', String(days));
    return this.http.post<DiagnosticReport>('/api/admin/diagnostic', null, { params });
  }
}
