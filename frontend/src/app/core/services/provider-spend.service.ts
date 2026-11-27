import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';

import { AdminCostService } from '../../admin/cost/admin-cost.service';
import { ProviderSpend } from '../../admin/cost/admin-cost.models';

/** Au-delà, une nouvelle demande relit : le canal en service peut avoir changé. */
export const PROVIDER_SPEND_FRESH_MS = 5 * 60 * 1000;

/**
 * **Les deux pots du mois** (F-279 / SF-279-02) — Anthropic et AWS Bedrock.
 *
 * <p><b>Une seule lecture pour tous les écrans</b> : la barre du terminal et l'écran Coût réel montrent le même fait,
 * comme le budget de la semaine (SF-133-15). Une nouvelle demande ne relit que si la lecture a plus de 5 minutes.</p>
 *
 * <p><b>Un échec ne dit rien</b> : sans droit (403) ou passerelle muette, `spend` reste `null` et la barre n'affiche
 * rien. Les montants de la plateforme ne quittent jamais le serveur pour qui n'est pas administrateur.</p>
 */
@Injectable({ providedIn: 'root' })
export class ProviderSpendService {
  private readonly cost = inject(AdminCostService);

  /** Les deux pots, ou `null` tant qu'ils n'ont pas été lus — ou s'ils sont inaccessibles. */
  readonly spend = signal<ProviderSpend | null>(null);

  private pending = false;
  private loadedAt = 0;

  /** Lit les pots, sauf si une lecture est en cours ou date de moins de 5 minutes. */
  load(now: number = Date.now()): void {
    if (this.pending || (this.loadedAt > 0 && now - this.loadedAt < PROVIDER_SPEND_FRESH_MS)) {
      return;
    }
    this.pending = true;
    this.cost
      .providers()
      .pipe(
        catchError(() => of(null)),
        tap(() => (this.pending = false)),
      )
      .subscribe((spend) => {
        this.loadedAt = now;
        if (spend !== null) {
          this.spend.set(spend);
        }
      });
  }

  /** Lecture explicite (écran Coût réel) : l'erreur remonte à l'appelant, la réponse remplace la lecture partagée. */
  read(): Observable<ProviderSpend> {
    return this.share(this.cost.providers());
  }

  /** [Relire maintenant] : la passerelle relit les deux factures ; la réponse remplace la lecture partagée. */
  refresh(): Observable<ProviderSpend> {
    return this.share(this.cost.refreshProviders());
  }

  private share(source: Observable<ProviderSpend>): Observable<ProviderSpend> {
    return source.pipe(
      map((spend) => {
        this.loadedAt = Date.now();
        this.spend.set(spend);
        return spend;
      }),
    );
  }
}
