import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { VideoSummary } from '../models/videos.models';

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
}
