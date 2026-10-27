import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ClientBoard } from '../models/client-board.models';

/**
 * **Le tableau de bord du client** (F-220 / SF-220-03) : tous les chantiers ouverts des sujets d'un poste
 * (SF-220-02). Aucun identifiant de compte : la gateway part du JWT et rend 404 sur le poste d'autrui.
 */
@Injectable({ providedIn: 'root' })
export class ClientBoardService {
  private readonly http = inject(HttpClient);

  /** Le tableau du poste `hostId`. */
  board(hostId: string): Observable<ClientBoard> {
    return this.http.get<ClientBoard>(`/api/runner-hosts/${hostId}/chantiers`);
  }
}
