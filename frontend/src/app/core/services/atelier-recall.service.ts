import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ThreadRecallResult } from '../../atelier/terminal/slash-panel-commands';

/**
 * **Le rappel à la demande** (F-165 / SF-165-06) : la lecture qui alimente la commande action
 * `/rappel <terme>`.
 *
 * <p><b>Une recherche, aucun tour.</b> C'est un simple `GET` de **lecture** vers la gateway — jamais la
 * boucle modèle. Le backend réutilise la recherche du recall F-162 (sémantique puis mot-clé), isolée
 * `user_id` + `requireOwned` : un utilisateur ne rappelle que **son** fil.</p>
 */
@Injectable({ providedIn: 'root' })
export class AtelierRecallService {
  private readonly http = inject(HttpClient);

  /** Cherche `query` dans l'historique du fil d'un projet. Lecture seule, isolée côté serveur. */
  recall(workspaceId: string, query: string): Observable<ThreadRecallResult> {
    return this.http.get<ThreadRecallResult>(
      `/api/workspaces/${workspaceId}/chat/recall`,
      { params: new HttpParams().set('q', query) },
    );
  }
}
