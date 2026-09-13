import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { PageSpace, PageSummary, PageVersionSummary } from '../models/pages.models';

/**
 * **Les pages** (F-109). Aucun appel ne porte d'identifiant de compte : la gateway part du JWT et ne rend
 * que les pages du compte.
 */
@Injectable({ providedIn: 'root' })
export class PagesService {
  private readonly http = inject(HttpClient);

  /** Une page du compte, avec un ticket de lecture frais — de la version courante, ou de `version`. */
  get(pageId: string, version?: number | null): Observable<PageSummary> {
    const params = version ? new HttpParams().set('version', String(version)) : undefined;
    return this.http.get<PageSummary>(`/api/pages/${pageId}`, { params });
  }

  /** Les pages du compte à un lieu (F-109 / SF-109-04). */
  list(hostId: string, space: PageSpace): Observable<PageSummary[]> {
    return this.http.get<PageSummary[]>('/api/pages', { params: { hostId, space } });
  }

  /** Les versions conservées d'une page, la plus récente d'abord. */
  versions(pageId: string): Observable<PageVersionSummary[]> {
    return this.http.get<PageVersionSummary[]>(`/api/pages/${pageId}/versions`);
  }

  /** Renomme une page. */
  rename(pageId: string, title: string): Observable<PageSummary> {
    return this.http.patch<PageSummary>(`/api/pages/${pageId}`, { title });
  }

  /** Supprime une page, ses versions et son contenu. */
  remove(pageId: string): Observable<void> {
    return this.http.delete<void>(`/api/pages/${pageId}`);
  }

  /** Supprime toutes les pages du compte à un lieu (clôture de mission). */
  removePlace(hostId: string, space: PageSpace): Observable<void> {
    return this.http.delete<void>('/api/pages', { params: { hostId, space } });
  }

  /** L'archive ZIP des pages d'un lieu. */
  exportPlace(hostId: string, space: PageSpace): Observable<HttpResponse<Blob>> {
    return this.http.get('/api/pages/export', { params: { hostId, space }, responseType: 'blob', observe: 'response' });
  }

  /** Le fichier HTML d'une version (courante par défaut), en téléchargement. */
  download(pageId: string, version?: number | null): Observable<HttpResponse<Blob>> {
    let params = new HttpParams().set('download', 'true');
    if (version) {
      params = params.set('version', String(version));
    }
    return this.http.get(`/api/pages/${pageId}/content`, { params, responseType: 'blob', observe: 'response' });
  }
}
