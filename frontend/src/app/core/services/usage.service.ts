import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { UsageView } from '../models/usage.models';

/**
 * Accès à l'API de consommation F-10. Le frontend ne communique qu'avec Claude Gateway (`/api/...`).
 * L'isolation des données est garantie côté backend via le `user_id` porté par le JWT (ajouté par
 * l'`authInterceptor`) : aucun identifiant utilisateur n'est transmis par le client.
 */
@Injectable({ providedIn: 'root' })
export class UsageService {
  private readonly http = inject(HttpClient);

  /** Consommation de tokens de l'utilisateur courant pour la période en cours. */
  getUsage(): Observable<UsageView> {
    return this.http.get<UsageView>('/api/usage');
  }
}
