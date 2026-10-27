import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AutonomyBoard, AutonomyRun } from '../models/autonomy.models';

/**
 * **Un chantier en autonome** (F-219) : lire l'état, lancer, reprendre la main. Aucun appel ne porte
 * d'identifiant de compte : la gateway part du JWT et rend 404 sur le terminal d'autrui.
 */
@Injectable({ providedIn: 'root' })
export class AutonomyService {
  private readonly http = inject(HttpClient);

  /** Les chantiers ouverts du sujet : préflight, course, estimation. */
  board(workspaceId: string): Observable<AutonomyBoard> {
    return this.http.get<AutonomyBoard>(`/api/workspaces/${workspaceId}/journey/autonomy`);
  }

  /** [Lancer en autonome] le chantier N — aucune confirmation (F-218). */
  launch(workspaceId: string, number: number): Observable<AutonomyRun> {
    return this.http.post<AutonomyRun>(`/api/workspaces/${workspaceId}/journey/chantiers/${number}/autonomy`, {});
  }

  /** [Reprendre la main] sur le chantier autonome du sujet. */
  stop(workspaceId: string): Observable<AutonomyRun> {
    return this.http.post<AutonomyRun>(`/api/workspaces/${workspaceId}/journey/autonomy/stop`, {});
  }

  /** Les courses du compte, tous sujets (F-220). */
  runs(): Observable<AutonomyRun[]> {
    return this.http.get<AutonomyRun[]>('/api/atelier/autonomy/runs');
  }
}
