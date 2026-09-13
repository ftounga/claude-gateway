import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RunnerUpdateProgress } from '../models/atelier.models';

/**
 * La mise à jour du runner d'un poste (F-111 / SF-111-04) : « Mettre à jour », « Forcer », et le journal.
 * Propriétaire du poste ou ADMIN ; la gateway vérifie, le runner télécharge et vérifie la signature.
 */
@Injectable({ providedIn: 'root' })
export class RunnerUpdateService {
  private readonly http = inject(HttpClient);

  /** Demande la mise à jour ; `force` relance une mise à jour en attente sans attendre la fin des activités. */
  request(hostId: string, force = false): Observable<RunnerUpdateProgress> {
    return this.http.post<RunnerUpdateProgress>(`/api/runner-hosts/${hostId}/runner-update`, { force });
  }

  /** Les 20 dernières mises à jour du poste. */
  journal(hostId: string): Observable<RunnerUpdateProgress[]> {
    return this.http.get<RunnerUpdateProgress[]>(`/api/runner-hosts/${hostId}/runner-update/journal`);
  }
}
