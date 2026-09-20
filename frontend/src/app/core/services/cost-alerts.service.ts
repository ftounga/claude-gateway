import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Une alerte de dépense telle que la Forge la reçoit (F-133 / SF-133-12).
 *
 * <p>`spentEur` et `budgetEur` sont `null` pour qui n'est pas administrateur : la passerelle les
 * retire de la réponse, elle ne compte pas sur l'écran pour les cacher. `percent`, lui, est
 * toujours là — il dit l'ampleur sans dire l'argent.</p>
 */
export interface ForgeCostAlert {
  scope: 'HOST' | 'TOTAL';
  hostId: string | null;
  hostName: string | null;
  spentEur: number | null;
  budgetEur: number | null;
  percent: number;
  level: 'NEAR' | 'EXCEEDED';
  weekStart: string;
}

/**
 * Les alertes de dépense de l'utilisateur courant (F-133 / SF-133-12).
 *
 * <p>Distinct d'`AdminCostService` : cette route-ci est ouverte à tout compte connecté, et ne rend
 * que ses propres postes. L'isolation est garantie côté passerelle par `user_id` — l'appel ne porte
 * aucun identifiant.</p>
 */
@Injectable({ providedIn: 'root' })
export class CostAlertsService {
  private readonly http = inject(HttpClient);

  /** Les alertes de la semaine en cours, sur les postes de l'appelant. */
  mine(): Observable<ForgeCostAlert[]> {
    return this.http.get<ForgeCostAlert[]>('/api/cost/alerts/mine');
  }
}
