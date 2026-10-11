import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ThreadContextSummary, ThreadHandoff } from '../../atelier/terminal/slash-panel-commands';

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

  /**
   * **La passation du fil** (F-117 / SF-117-09) : le résumé de compaction que Claude relira au prochain
   * tour. Lecture seule, isolée côté serveur (`requireOwned`) ; `summary` vaut `null` sans résumé.
   */
  handoff(workspaceId: string): Observable<ThreadHandoff> {
    return this.http.get<ThreadHandoff>(`/api/workspaces/${workspaceId}/chat/handoff`);
  }

  /**
   * Corrige la passation (F-117 / SF-117-09). Le serveur refuse un texte vide, trop long ou privé d'une
   * des cinq rubriques (400), une correction sans passation existante ou pendant un tour (409).
   */
  correctHandoff(workspaceId: string, summary: string): Observable<ThreadHandoff> {
    return this.http.put<ThreadHandoff>(`/api/workspaces/${workspaceId}/chat/handoff`, { summary });
  }
}
