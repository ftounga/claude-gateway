import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DiagnosticReport, SourceHypothesis } from './admin-diagnostic.models';

/**
 * **Le diagnostic du produit** (F-156 / SF-156-05) — administration.
 *
 * <p>Il se lance **à la demande** : le lancer à chaque fermeture de session reviendrait à
 * ré-analyser les mêmes données pour la même conclusion.</p>
 */
@Injectable({ providedIn: 'root' })
export class AdminDiagnosticService {
  private readonly http = inject(HttpClient);

  /**
   * Lance le diagnostic sur les `days` derniers jours.
   *
   * <p>**Aucun jeton n'est consommé.** Avec `workspaceId`, le code du dépôt est lu pour enrichir
   * les constats — la lecture reste gratuite, seule l'hypothèse coûte.</p>
   */
  run(days: number, workspaceId?: string | null): Observable<DiagnosticReport> {
    let params = new HttpParams().set('days', String(days));
    if (workspaceId) {
      params = params.set('workspaceId', workspaceId);
    }
    return this.http.post<DiagnosticReport>('/api/admin/diagnostic', null, { params });
  }

  /**
   * Demande une **hypothèse** sur une capacité, tirée de la lecture de son code.
   *
   * <p>**C'est la seule opération du diagnostic qui coûte des jetons.** Rend un corps vide (204)
   * quand il n'y avait rien à en tirer.</p>
   */
  explain(workspaceId: string, capabilityId: string): Observable<SourceHypothesis | null> {
    const params = new HttpParams()
      .set('workspaceId', workspaceId)
      .set('capabilityId', capabilityId);
    return this.http.post<SourceHypothesis | null>('/api/admin/diagnostic/hypothesis', null, { params });
  }
}
