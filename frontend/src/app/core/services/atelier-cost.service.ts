import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ThreadCostSummary } from '../../atelier/terminal/slash-panel-commands';

/**
 * **L'économie du fil courant** (F-165 / SF-165-02) : la lecture qui alimente la commande vue `/cout`.
 *
 * <p><b>Une VUE, aucun tour.</b> C'est un simple `GET` de **lecture** vers la gateway — jamais la boucle
 * modèle. « Vérifier son coût ne doit rien coûter » (cadrage F-165). L'isolation est tenue côté serveur
 * (`requireOwned` + filtre `user_id`/`workspace_id`) : un utilisateur ne lit que **son** fil.</p>
 *
 * <p>Pas de cache ni de partage ici (contrairement au budget hebdomadaire) : chaque `/cout` demande
 * l'état <b>au moment où on le tape</b>, et le panneau est éphémère.</p>
 */
@Injectable({ providedIn: 'root' })
export class AtelierCostService {
  private readonly http = inject(HttpClient);

  /** L'économie du fil d'un projet. Lecture seule, isolée côté serveur. */
  costSummary(workspaceId: string): Observable<ThreadCostSummary> {
    return this.http.get<ThreadCostSummary>(
      `/api/workspaces/${workspaceId}/chat/cost-summary`,
    );
  }
}
