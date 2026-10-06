import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ClosedChantier, JourneyMode, SubjectJourney } from '../models/journey.models';

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

  /**
   * Le menu du terminal : Libre ou Guidé (SF-176-01). `title` titre le chantier qui s'ouvre
   * (SF-176-11, facultatif).
   */
  setMode(workspaceId: string, mode: JourneyMode, title?: string): Observable<SubjectJourney> {
    return this.http.put<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/mode`,
      title ? { mode, title } : { mode });
  }

  /** Les chantiers clos du sujet, le plus récent d'abord (SF-176-11). */
  chantiers(workspaceId: string): Observable<ClosedChantier[]> {
    return this.http.get<ClosedChantier[]>(`/api/workspaces/${workspaceId}/journey/chantiers`);
  }

  /** [Passer en guidé] (SF-176-02). */
  acceptGuided(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/guided-proposal/accept`, {});
  }

  /** [Valider le plan] (SF-176-03) : un clic valide le plan entier, à la version vue. */
  validatePlan(workspaceId: string, version: number): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/plan/validate`, { version });
  }

  /** [Planifier] (SF-176-05) : le diagnostic est accepté, le sujet passe en Plan. */
  confirmDiagnosis(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/diagnosis/confirm`, {});
  }

  /** [Continuer l'investigation] (SF-176-05). */
  dismissDiagnosis(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/diagnosis/dismiss`, {});
  }

  /** [Clore le sujet] (SF-176-05). */
  close(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/close`, {});
  }

  /** [Pas encore] — la clôture proposée est écartée (SF-176-05). */
  dismissClose(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/close/dismiss`, {});
  }

  /** [Rester libre] (SF-176-02) : la proposition ne revient pas sur ce sujet. */
  declineGuided(workspaceId: string): Observable<SubjectJourney> {
    return this.http.post<SubjectJourney>(`/api/workspaces/${workspaceId}/journey/guided-proposal/decline`, {});
  }
}
