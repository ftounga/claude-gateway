import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  VideoComment,
  VideoCommentsSent,
  VideoGallery,
  VideoGalleryPlace,
  VideoSummary,
  VideoVersionInfo,
} from '../models/videos.models';

/**
 * **Les vidéos** (F-213). Aucun appel ne porte d'identifiant de compte : la gateway part du JWT et ne rend que
 * les vidéos du compte.
 */
@Injectable({ providedIn: 'root' })
export class VideosService {
  private readonly http = inject(HttpClient);

  /** Une vidéo du compte, avec des adresses de lecture fraîches — de la version courante, ou de `version`. */
  get(videoId: string, version?: number | null): Observable<VideoSummary> {
    const params = version ? new HttpParams().set('version', String(version)) : undefined;
    return this.http.get<VideoSummary>(`/api/videos/${videoId}`, { params });
  }

  /** La galerie d'un sujet ou d'un poste (F-213 / SF-213-03) : séries et vidéos seules, jamais les sons. */
  gallery(place: VideoGalleryPlace): Observable<VideoGallery> {
    const params: Record<string, string> =
      'workspaceId' in place ? { workspaceId: place.workspaceId } : { hostId: place.hostId };
    return this.http.get<VideoGallery>('/api/videos', { params });
  }

  /** Supprime une vidéo, ses versions et ses objets. */
  remove(videoId: string): Observable<void> {
    return this.http.delete<void>(`/api/videos/${videoId}`);
  }

  /** Supprime une série et tous ses épisodes. */
  removeSeries(seriesId: string): Observable<void> {
    return this.http.delete<void>(`/api/video-series/${seriesId}`);
  }

  /** La durée lue par le navigateur corrige celle annoncée par l'agent. */
  reportDuration(videoId: string, durationS: number): Observable<void> {
    return this.http.put<void>(`/api/videos/${videoId}/duration`, { durationS });
  }

  /** Les versions conservées, la plus récente d'abord. */
  versions(videoId: string): Observable<VideoVersionInfo[]> {
    return this.http.get<VideoVersionInfo[]>(`/api/videos/${videoId}/versions`);
  }

  // ------------------------------------------------------------------ retours à la seconde (F-214)

  /** Les retours d'une vidéo, toutes versions (par version puis instant). */
  comments(videoId: string): Observable<VideoComment[]> {
    return this.http.get<VideoComment[]>(`/api/videos/${videoId}/comments`);
  }

  /** Pose un retour sur un instant (ou une plage) d'une version. */
  addComment(videoId: string, body: { version: number; tStartMs: number; tEndMs: number | null; text: string }):
    Observable<VideoComment> {
    return this.http.post<VideoComment>(`/api/videos/${videoId}/comments`, body);
  }

  /** Rouvre un retour envoyé ou clos. */
  reopenComment(videoId: string, commentId: string): Observable<VideoComment> {
    return this.http.patch<VideoComment>(`/api/videos/${videoId}/comments/${commentId}`, { status: 'OUVERT' });
  }

  /** Supprime un retour. */
  removeComment(videoId: string, commentId: string): Observable<void> {
    return this.http.delete<void>(`/api/videos/${videoId}/comments/${commentId}`);
  }

  /** « Envoyer à l'agent » / « Copier » : le message composé ; les retours ouverts de la version passent ENVOYE. */
  sendComments(videoId: string, version: number): Observable<VideoCommentsSent> {
    return this.http.post<VideoCommentsSent>(`/api/videos/${videoId}/comments/send`, { version });
  }
}
