import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import {
  CreateMeetingRequest,
  MeetingActionsToRadar,
  MeetingAnswer,
  MeetingCardPromotion,
  MeetingInsights,
  TeamsMeeting,
} from '../models/teams-meeting.models';

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

  /**
   * « Rejoindre » (SF-128-16, 1ᵉʳ temps) : ouvre l'URL dans le Chrome managé, entre réellement en
   * réunion (clic « Rejoindre maintenant » + détection du vrai in-call) et crée l'artefact en `JOINED`.
   * L'enregistrement ne démarre pas ici : voir {@link startCapture}.
   */
  create(hostId: string, request: CreateMeetingRequest): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(this.base(hostId), request);
  }

  /**
   * « Démarrer l'enregistrement » (SF-128-16, 2ᵉ temps) : sur une réunion déjà rejointe et in-call,
   * lance la capture d'onglet (`JOINED → RECORDING`). Le capteur survit car il n'y a plus de navigation.
   */
  startCapture(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(`${this.base(hostId)}/${meetingId}/capture-start`, {});
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

  /**
   * L'audio de la réunion, chargé en `Blob` via `HttpClient` (SF-128-10). On passe par le client HTTP
   * — et non un `<audio src>` natif — pour que le JWT (ajouté par l'intercepteur sur `/api`) parte bien :
   * un élément média natif n'emporte pas l'en-tête `Authorization`. Le blob alimente ensuite un
   * `URL.createObjectURL` pour l'écoute et le téléchargement.
   */
  audioBlob(hostId: string, meetingId: string): Observable<Blob> {
    return this.http.get(`${this.base(hostId)}/${meetingId}/audio`, { responseType: 'blob' });
  }

  /** Les identifiants des images clés (deck reconstitué). */
  imageIds(hostId: string, meetingId: string): Observable<string[]> {
    return this.http
      .get<{ imageIds: string[] }>(`${this.base(hostId)}/${meetingId}/images`)
      .pipe(map((response) => response.imageIds ?? []));
  }

  /** Une image clé, chargée en `Blob` (même raison JWT que l'audio). */
  imageBlob(hostId: string, meetingId: string, imageId: string): Observable<Blob> {
    return this.http.get(`${this.base(hostId)}/${meetingId}/images/${encodeURIComponent(imageId)}`, {
      responseType: 'blob',
    });
  }

  /** Déclenche la transcription (SF-128-04) : 202 si enfilé, 503 si STT non configuré. */
  transcribe(hostId: string, meetingId: string): Observable<TeamsMeeting> {
    return this.http.post<TeamsMeeting>(`${this.base(hostId)}/${meetingId}/transcribe`, {});
  }

  /** Le texte du transcript (SF-128-04), ou 404 s'il n'y en a pas. */
  transcript(hostId: string, meetingId: string): Observable<string> {
    return this.http.get(`${this.base(hostId)}/${meetingId}/transcript`, { responseType: 'text' });
  }

  /** Analyse la réunion (SF-128-05) : résumé, points clés, décisions, actions. */
  insights(hostId: string, meetingId: string): Observable<MeetingInsights> {
    return this.http.post<MeetingInsights>(`${this.base(hostId)}/${meetingId}/insights`, {});
  }

  /** Pose une question libre à l'agent sur la réunion (SF-128-05). */
  ask(hostId: string, meetingId: string, question: string): Observable<MeetingAnswer> {
    return this.http.post<MeetingAnswer>(`${this.base(hostId)}/${meetingId}/ask`, { question });
  }

  /**
   * Range les faits durables de la réunion dans la carte du poste (SF-128-11) : « le travail est
   * jetable, le savoir est durable ». Rend le bilan (ce qui a été rangé, où, et pourquoi rien parfois).
   */
  promoteToCard(hostId: string, meetingId: string): Observable<MeetingCardPromotion> {
    return this.http.post<MeetingCardPromotion>(
      `${this.base(hostId)}/${meetingId}/promote-to-card`,
      {},
    );
  }

  /**
   * Pousse les actions retenues d'une réunion (SF-128-05) dans le **Radar** comme engagements « À faire
   * par moi » (SF-128-06), rattachés à un sujet, avec la réunion pour preuve. Sans `subjectId`, le
   * serveur reprend le sujet de la réunion ; s'il n'y en a pas, le bilan porte `needsSubject`.
   */
  pushActionsToRadar(
    hostId: string,
    meetingId: string,
    actions: string[],
    subjectId: string | null,
  ): Observable<MeetingActionsToRadar> {
    return this.http.post<MeetingActionsToRadar>(
      `${this.base(hostId)}/${meetingId}/actions-to-radar`,
      { subjectId, actions },
    );
  }
}
