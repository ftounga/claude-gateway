import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { BilanDetail, BilanSummary } from './admin-bilans.models';

/**
 * **Les bilans de session** (F-155 / SF-155-04) — routes d'administration. Aucun appel ne porte
 * d'identifiant de compte : la gateway part du JWT, et la garde d'administration est la sienne.
 */
@Injectable({ providedIn: 'root' })
export class AdminBilansService {
  private readonly http = inject(HttpClient);

  /** Les bilans du compte, les plus récents d'abord. */
  list(): Observable<BilanSummary[]> {
    return this.http.get<BilanSummary[]>('/api/admin/bilans');
  }

  /** Un bilan ouvert : ses chiffres, son relevé, ses suggestions. */
  open(id: string): Observable<BilanDetail> {
    return this.http.get<BilanDetail>(`/api/admin/bilans/${id}`);
  }

  /**
   * Produit le bilan de la session en cours d'un projet — le « proposé d'un clic ».
   *
   * <p>Rend un corps vide (204) quand il n'y avait rien à dire.</p>
   */
  produce(workspaceId: string): Observable<BilanSummary | null> {
    const params = new HttpParams().set('workspaceId', workspaceId);
    return this.http.post<BilanSummary | null>('/api/admin/bilans', null, { params });
  }
}
