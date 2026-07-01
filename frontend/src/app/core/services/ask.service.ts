import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AskRequest, AskResponse } from '../models/ask.models';

/**
 * Accès à l'API de Q&A documentaire (F-07). Le frontend ne communique qu'avec Claude Gateway
 * (`/api/ask`), jamais directement avec un fournisseur d'embeddings/IA. L'isolation des données est
 * garantie côté backend via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`) : le
 * service n'envoie jamais d'identifiant utilisateur.
 */
@Injectable({ providedIn: 'root' })
export class AskService {
  private readonly http = inject(HttpClient);

  /** Pose une question ancrée sur les documents indexés et renvoie la réponse citée. */
  ask(body: AskRequest): Observable<AskResponse> {
    return this.http.post<AskResponse>('/api/ask', body);
  }
}
