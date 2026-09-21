import { Injectable, inject, signal } from '@angular/core';
import { shareReplay, catchError, of, tap } from 'rxjs';

import { AdminCostService } from '../../admin/cost/admin-cost.service';
import { AdminCostClient, AdminCostSummary } from '../../admin/cost/admin-cost.models';

/**
 * **Où l'on en est du budget de la semaine** (F-133 / SF-133-15).
 *
 * <p>Relevé le 2026-09-22 : deux budgets posés, 42 % consommés, et <b>rien nulle part</b> — le
 * bandeau d'alerte (SF-133-12) ne parle qu'au-delà de 80 %, et le budget lui-même ne vivait que dans
 * la console d'administration. On avait livré l'alerte quand ça déborde, sans le compteur quand tout
 * va bien.</p>
 *
 * <p><b>Une seule lecture pour tous les écrans.</b> La Forge et le terminal montrent le même fait :
 * s'ils l'interrogeaient chacun de leur côté, ils finiraient par afficher deux chiffres différents
 * au même instant. La réponse est donc partagée et mise en cache jusqu'à demande explicite.</p>
 *
 * <p><b>Un échec ne dit rien.</b> Pas d'accès (403), gateway muette : le service rend `null` et les
 * écrans n'affichent rien. C'est un indicateur, pas un service — et le montant ne doit de toute
 * façon jamais quitter le serveur pour qui n'est pas administrateur.</p>
 */
@Injectable({ providedIn: 'root' })
export class WeeklyBudgetService {
  private readonly cost = inject(AdminCostService);

  /** Le résumé de la semaine, ou `null` tant qu'il n'a pas été lu — ou s'il est inaccessible. */
  readonly summary = signal<AdminCostSummary | null>(null);

  private pending = false;

  /** Lit le résumé une fois. Les appels suivants ne relancent rien. */
  load(): void {
    if (this.pending || this.summary() !== null) {
      return;
    }
    this.pending = true;
    this.cost
      .summary('week')
      .pipe(
        catchError(() => of(null)),
        tap(() => (this.pending = false)),
        shareReplay(1),
      )
      .subscribe((summary) => this.summary.set(summary));
  }

  /** La ligne d'un poste, ou `null` s'il n'a pas de budget applicable. */
  clientOf(hostId: string | null): AdminCostClient | null {
    if (!hostId) {
      return null;
    }
    const client = this.summary()?.clients.find((line) => line.hostId === hostId);
    // Sans budget, aucune part : on n'invente pas un plafond pour pouvoir afficher une barre.
    return client && client.budgetEur !== null ? client : null;
  }
}
