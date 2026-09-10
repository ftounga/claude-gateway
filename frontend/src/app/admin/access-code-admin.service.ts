import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AccessCodeAdminView,
  IssueAccessCodeRequest,
  IssuedAccessCode,
} from './access-code-admin.models';

/**
 * Émission et suivi des codes d'accès à durée limitée (F-62 / SF-62-03).
 *
 * L'autorisation réelle (rôle ADMIN) est appliquée côté backend, par la garde unique du produit.
 * Cet écran ne la rejoue pas : il **affiche** son refus.
 */
@Injectable({ providedIn: 'root' })
export class AccessCodeAdminService {
  private readonly http = inject(HttpClient);

  /** Tous les codes émis, avec leur trace de consommation. Jamais de code en clair. */
  list(): Observable<AccessCodeAdminView[]> {
    return this.http.get<AccessCodeAdminView[]>('/api/admin/access-codes');
  }

  /**
   * Émet un code. La réponse porte le clair — **la seule fois** où il est lisible : il n'est pas
   * stocké et ne pourra pas être retrouvé.
   */
  issue(label: string, assignedEmail?: string): Observable<IssuedAccessCode> {
    const body: IssueAccessCodeRequest = assignedEmail
      ? { label, assignedEmail }
      : { label };
    return this.http.post<IssuedAccessCode>('/api/admin/access-codes', body);
  }
}
