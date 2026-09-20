import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AdminCostAlert,
  AdminCostBudgets,
  AdminCostPeriod,
  AdminCostSummary,
} from './admin-cost.models';

/**
 * Accès au coût réel et aux budgets (F-133 / SF-133-04, 06, 07).
 *
 * L'autorisation réelle est appliquée **côté passerelle**, qui répond `403` à tout appelant qui
 * n'est pas administrateur. Rien n'est gardé ici : une garde côté navigateur ne protège rien.
 */
@Injectable({ providedIn: 'root' })
export class AdminCostService {
  private readonly http = inject(HttpClient);

  /** Dépense, budget et part de la période — en une lecture. */
  summary(period: AdminCostPeriod): Observable<AdminCostSummary> {
    return this.http.get<AdminCostSummary>('/api/admin/cost/summary', {
      params: new HttpParams().set('period', period),
    });
  }

  /** Les alertes en cours de la semaine. */
  alerts(): Observable<AdminCostAlert[]> {
    return this.http.get<AdminCostAlert[]>('/api/admin/cost/alerts');
  }

  /** Le budget hebdomadaire par défaut. */
  setDefaultBudget(amountEur: number): Observable<AdminCostBudgets> {
    return this.http.put<AdminCostBudgets>('/api/admin/cost/budget', { amountEur });
  }

  /** Le budget hebdomadaire d'un client. */
  setHostBudget(hostId: string, amountEur: number): Observable<AdminCostBudgets> {
    return this.http.put<AdminCostBudgets>(`/api/admin/cost/budget/${hostId}`, { amountEur });
  }

  /** Retire le budget propre d'un client : il retombe sur le défaut. */
  clearHostBudget(hostId: string): Observable<AdminCostBudgets> {
    return this.http.delete<AdminCostBudgets>(`/api/admin/cost/budget/${hostId}`);
  }
}
