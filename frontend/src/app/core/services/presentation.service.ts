import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Presentation, PresentationSpace } from '../models/presentation.models';

/**
 * Accès à l'API des présentations (F-129 / SF-129-02). Le frontend ne fait que demander ; l'isolation
 * `user_id` est garantie côté backend via le JWT (ajouté par l'`authInterceptor`) — jamais d'identifiant
 * utilisateur dans la requête. La **production** passe par l'agent (outil `presentation_publish`), pas
 * par ce service, qui est en lecture / téléchargement / suppression.
 */
@Injectable({ providedIn: 'root' })
export class PresentationService {
  private readonly http = inject(HttpClient);

  /** Les présentations d'un lieu (poste/client, espace), la plus récente d'abord. */
  list(hostId: string, space: PresentationSpace): Observable<Presentation[]> {
    return this.http.get<Presentation[]>('/api/presentations', { params: { hostId, space } });
  }

  /** Une présentation. */
  get(id: string): Observable<Presentation> {
    return this.http.get<Presentation>(`/api/presentations/${id}`);
  }

  /** Le vrai .pptx (réponse binaire + en-têtes, pour un téléchargement porteur du JWT). */
  download(id: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/api/presentations/${id}/pptx`, { responseType: 'blob', observe: 'response' });
  }

  /** Une image de slide (SF-129-03), en blob (JWT porté). */
  slide(id: string, index: number): Observable<Blob> {
    return this.http.get(`/api/presentations/${id}/slides/${index}`, { responseType: 'blob' });
  }

  /** Supprime une présentation. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/presentations/${id}`);
  }
}
