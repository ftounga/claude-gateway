import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateMeetingRequest, TeamsMeeting } from '../models/teams-meeting.models';

/**
 * Accès HTTP aux API Réunions de la Vigie (F-128 / SF-128-01). Le JWT est ajouté par
 * l'intercepteur (`authInterceptor`) ; le compte/poste sont dérivés côté serveur — le `hostId` du
 * chemin est le poste, jamais un identifiant de compte.
 */
@Injectable({ providedIn: 'root' })
export class TeamsMeetingService {
  private readonly http = inject(HttpClient);

  private base(hostId: string): string {
    return `/api/vigie/hosts/${hostId}/meetings`;
  }

  /** Les réunions du poste, de la plus récente à la plus ancienne. */
  list(hostId: string): Observable<TeamsMeeting[]> {
    return this.http.get<TeamsMeeting[]>(this.base(hostId));
  }

  /** Une réunion du poste. */
  get(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.get<TeamsMeeting>(`${this.base(hostId)}/${meetingId}`);
  }

  /** « Rejoindre & capturer » : ouvre l'URL dans le Chrome managé et crée l'artefact. */
  create(hostId: string, request: CreateMeetingRequest): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(this.base(hostId), request);
  }

  /** Arrête la capture. */
  stop(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(`${this.base(hostId)}/${meetingId}/stop`, {});
  }

  /** Met la capture en pause. */
  pause(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(`${this.base(hostId)}/${meetingId}/pause`, {});
  }

  /** Reprend une capture en pause. */
  resume(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(`${this.base(hostId)}/${meetingId}/resume`, {});
  }
}
