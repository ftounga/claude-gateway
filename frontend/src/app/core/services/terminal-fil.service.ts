import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { PosteTurns } from '../models/terminal-fil.models';

/**
 * **Le fil des tâches** (F-191 / SF-191-02) : les tours en cours — et finis depuis moins de dix
 * minutes — de tout le poste du terminal. Lecture seule, aucun appel modèle ; la gateway part du JWT
 * et ne rend que ce que le compte possède.
 */
@Injectable({ providedIn: 'root' })
export class TerminalFilService {
  private readonly http = inject(HttpClient);

  turns(workspaceId: string): Observable<PosteTurns> {
    return this.http.get<PosteTurns>(`/api/workspaces/${workspaceId}/fil`);
  }
}
