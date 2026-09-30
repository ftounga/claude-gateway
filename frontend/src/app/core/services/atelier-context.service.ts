import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ThreadContextSummary } from '../../atelier/terminal/slash-panel-commands';

/**
 * **L'état mémoire du fil courant** (F-165 / SF-165-03) : la lecture qui alimente la commande vue
 * `/contexte`.
 *
 * <p><b>Une VUE, aucun tour.</b> C'est un simple `GET` de **lecture** vers la gateway — jamais la boucle
 * modèle, jamais un recalcul côté modèle. « Vérifier son coût ne doit rien coûter » (cadrage F-165) vaut
 * aussi pour vérifier sa mémoire. L'isolation est tenue côté serveur (`requireOwned` + filtre
 * `user_id`/`workspace_id`) : un utilisateur ne lit que **son** fil.</p>
 */
@Injectable({ providedIn: 'root' })
export class AtelierContextService {
  private readonly http = inject(HttpClient);

  /** L'état mémoire du fil d'un projet. Lecture seule, isolée côté serveur. */
  contextSummary(workspaceId: string): Observable<ThreadContextSummary> {
    return this.http.get<ThreadContextSummary>(
      `/api/workspaces/${workspaceId}/chat/context-summary`,
    );
  }
}
