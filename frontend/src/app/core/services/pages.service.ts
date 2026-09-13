import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { PageSummary } from '../models/pages.models';

/**
 * **Les pages** (F-109). Aucun appel ne porte d'identifiant de compte : la gateway part du JWT et ne rend
 * que les pages du compte.
 */
@Injectable({ providedIn: 'root' })
export class PagesService {
  private readonly http = inject(HttpClient);

  /** Une page du compte, avec un ticket de lecture frais. */
  get(pageId: string): Observable<PageSummary> {
    return this.http.get<PageSummary>(`/api/pages/${pageId}`);
  }
}
