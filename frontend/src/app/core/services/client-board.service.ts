import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ChantierSnooze, ClientBoard } from '../models/client-board.models';

/**
 * **Le tableau de bord du client** (F-220 / SF-220-03) : tous les chantiers ouverts des sujets d'un poste
 * (SF-220-02), et leur report (SF-220-05). Aucun identifiant de compte : la gateway part du JWT et rend 404 sur
 * le poste ou le terminal d'autrui.
 */
@Injectable({ providedIn: 'root' })
export class ClientBoardService {
  private readonly http = inject(HttpClient);

  /** Le tableau du poste `hostId`. */
  board(hostId: string): Observable<ClientBoard> {
    return this.http.get<ClientBoard>(`/api/runner-hosts/${hostId}/chantiers`);
  }

  /** [Reporter] le chantier N de `days` jours (1 à 30). */
  snooze(workspaceId: string, number: number, days: number): Observable<ChantierSnooze> {
    return this.http.post<ChantierSnooze>(`/api/workspaces/${workspaceId}/journey/chantiers/${number}/snooze`,
      { days });
  }

  /** [Ne plus reporter] le chantier N. */
  unsnooze(workspaceId: string, number: number): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/journey/chantiers/${number}/snooze`);
  }
}
