import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AccountExport } from '../models/account.models';
import { MessageResponse } from '../models/auth.models';

/**
 * Accès à l'API RGPD du compte (F-11). Le frontend ne dialogue qu'avec Claude Gateway
 * (`/api/...`) ; l'isolation des données est garantie côté backend via le `user_id` porté par le
 * JWT (ajouté par l'`authInterceptor`). Aucun `user_id` n'est jamais transmis par le client.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);

  /** Export RGPD (portabilité) de l'intégralité des données de l'utilisateur courant. */
  exportData(): Observable<AccountExport> {
    return this.http.get<AccountExport>('/api/account/export');
  }

  /** Suppression définitive du compte courant et de toutes ses données. */
  deleteAccount(): Observable<MessageResponse> {
    return this.http.delete<MessageResponse>('/api/account');
  }
}
