import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { JourneyMode, SubjectJourney } from '../models/journey.models';

/**
 * **Le parcours du sujet d'un terminal** (F-176). Aucun appel ne porte d'identifiant de compte : la
 * gateway part du JWT et rend 404 sur le terminal d'autrui.
 */
@Injectable({ providedIn: 'root' })
export class JourneyService {
  private readonly http = inject(HttpClient);

  /** Le mode et la phase du sujet ; Libre s'il n'a jamais été décidé. */
  get(workspaceId: string): Observable<SubjectJourney> {
    return this.http.get<SubjectJourney>(`/api/workspaces/${workspaceId}/journey`);
  }

  /** Le menu du terminal : Libre ou Guidé (SF-176-01). */
  setMode(workspaceId: string, mode: JourneyMode): Observable<SubjectJourney> {
    return this.http.put<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/mode`, { mode });
  }
}
