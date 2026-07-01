import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { TemplateRequest, TemplateResponse } from '../models/template.models';

/**
 * Accès à l'API des modèles de prompts réutilisables (F-13). Le frontend ne communique qu'avec
 * Claude Gateway (`/api/templates`). L'isolation des données est garantie côté backend via le
 * `user_id` porté par le JWT (ajouté par l'`authInterceptor`) : le service n'envoie jamais
 * d'identifiant utilisateur.
 */
@Injectable({ providedIn: 'root' })
export class TemplatesService {
  private readonly http = inject(HttpClient);

  /** Liste des modèles de l'utilisateur courant (les plus récemment modifiés d'abord). */
  list(): Observable<TemplateResponse[]> {
    return this.http.get<TemplateResponse[]>('/api/templates');
  }

  /** Détail d'un modèle possédé. */
  get(id: string): Observable<TemplateResponse> {
    return this.http.get<TemplateResponse>(`/api/templates/${id}`);
  }

  /** Crée un modèle et renvoie sa représentation. */
  create(body: TemplateRequest): Observable<TemplateResponse> {
    return this.http.post<TemplateResponse>('/api/templates', body);
  }

  /** Met à jour un modèle possédé. */
  update(id: string, body: TemplateRequest): Observable<TemplateResponse> {
    return this.http.put<TemplateResponse>(`/api/templates/${id}`, body);
  }

  /** Supprime définitivement un modèle possédé. Renvoie `204 No Content`. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/templates/${id}`);
  }
}
