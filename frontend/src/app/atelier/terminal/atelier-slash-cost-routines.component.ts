import { Component, effect, inject, input, signal } from '@angular/core';

import { Routine, eur, monthPercent } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';

/**
 * **Les routines dans `/cout`** (F-183 / SF-183-03, D3) : par routine du sujet, le budget par exécution,
 * la dépense du mois face au budget du mois (barre) et le coût du dernier passage. Une VUE : une lecture
 * REST, jamais un tour. Absente quand le sujet n'a pas de routine.
 *
 * <p>Les montants sont en **dollars** — la monnaie dans laquelle l'utilisateur a fixé ses budgets — alors
 * que le haut du panneau parle euros (lecture au taux du moment) : c'est dit dans le titre.</p>
 */
@Component({
  selector: 'app-atelier-slash-cost-routines',
  template: `
    @if (routines().length > 0) {
      <section class="cost-routines" aria-label="Routines de ce sujet">
        <h4 class="cost-routines__title">Routines de ce sujet (en €)</h4>
        <ul class="cost-routines__list">
          @for (routine of routines(); track routine.id) {
            <li class="cost-routine">
              <div class="cost-routine__head">
                <span class="cost-routine__name">{{ routine.name }}</span>
                <span class="cost-routine__figure">{{ money(routine.monthSpentEur) }} / {{ money(routine.monthlyBudgetEur) }} ce mois</span>
              </div>
              <div class="cost-routine__bar" role="img"
                   [attr.aria-label]="percent(routine) + ' % du budget du mois'">
                <span class="cost-routine__fill" [style.width.%]="percent(routine)"></span>
              </div>
              <p class="cost-routine__detail">
                {{ money(routine.maxRunEur) }} max par exécution
                @if (routine.lastRun?.costUsd) {
                  · dernier passage {{ money(routine.lastRun?.costEur) }}
                }
                @if (!routine.active) {
                  · en pause
                }
              </p>
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .cost-routines { margin-top: var(--cg-space-4); }
    .cost-routines__title {
      margin: 0 0 var(--cg-space-2); font-size: 12px; font-weight: 600; letter-spacing: 0.02em;
      color: var(--cg-text-secondary);
    }
    .cost-routines__list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--cg-space-2); }
    .cost-routine__head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: var(--cg-space-2); }
    .cost-routine__name { font-weight: 600; overflow-wrap: anywhere; }
    .cost-routine__figure { font-variant-numeric: tabular-nums; }
    .cost-routine__bar {
      margin-top: var(--cg-space-1); height: 6px; border-radius: 3px; background: var(--cg-divider); overflow: hidden;
    }
    .cost-routine__fill { display: block; height: 100%; background: var(--cg-orange-2); }
    .cost-routine__detail {
      margin: var(--cg-space-1) 0 0; font-size: 12px; color: var(--cg-text-secondary);
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class AtelierSlashCostRoutinesComponent {
  private readonly service = inject(RoutineService);

  /** Le sujet du terminal. */
  readonly workspaceId = input<string | null>(null);

  /** Les routines validées du sujet (les propositions n'ont rien dépensé). */
  readonly routines = signal<Routine[]>([]);

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      if (!id) {
        this.routines.set([]);
        return;
      }
      this.service.list(id).subscribe({
        next: (routines) => this.routines.set(routines.filter((r) => !r.proposed)),
        // Une lecture en échec n'abîme pas /cout : la section reste absente.
        error: () => this.routines.set([]),
      });
    });
  }

  percent(routine: Routine): number {
    return monthPercent(routine);
  }

  money(value: number | null | undefined): string {
    return eur(value);
  }
}
