import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { UsageReportView } from '../models/usage-report.models';

/**
 * Accès à l'API du rapport d'usage & coût F-16. Le frontend ne communique qu'avec Claude Gateway
 * (`/api/usage/report`). L'isolation des données est garantie côté backend via le `user_id` porté
 * par le JWT (ajouté par l'`authInterceptor`) : aucun identifiant utilisateur n'est transmis.
 */
@Injectable({ providedIn: 'root' })
export class UsageReportService {
  private readonly http = inject(HttpClient);

  /** Historique mensuel de consommation et coût estimé de l'utilisateur courant. */
  getReport(): Observable<UsageReportView> {
    return this.http.get<UsageReportView>('/api/usage/report');
  }
}
