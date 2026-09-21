import { Component, computed, inject, input } from '@angular/core';

import { WeeklyBudgetService } from '../../core/services/weekly-budget.service';

/** Au-delà de cette part, on prévient — **le même seuil que l'alerte** (F-133 / SF-133-06). */
export const NEAR_PERCENT = 80;

/**
 * **Le budget de la semaine, là où l'on travaille** (F-133 / SF-133-15).
 *
 * <p>Deux densités, un seul fait : la Forge écrit la phrase entière, le terminal la resserre dans sa
 * barre. Les deux lisent le même résumé — deux lectures finiraient par montrer deux chiffres du même
 * instant.</p>
 *
 * <p><b>Les seuils viennent de l'alerte</b>, ils ne sont pas redéfinis ici : si l'un disait 80 % et
 * l'autre 75 %, l'écran affirmerait deux choses du même budget.</p>
 *
 * <p><b>Rien ne s'affiche</b> sans budget applicable, sans droit de lecture, ou si l'appel échoue.</p>
 */
@Component({
  selector: 'app-weekly-budget',
  template: `
    @if (line(); as budget) {
      <span
        class="weekly-budget"
        [class.weekly-budget--compact]="compact()"
        [class.weekly-budget--near]="level() === 'near'"
        [class.weekly-budget--over]="level() === 'over'"
        [title]="title()"
        role="status"
      >
        <span class="weekly-budget__text num">{{ label() }}</span>
        <span class="weekly-budget__gauge" aria-hidden="true">
          <span class="weekly-budget__fill" [style.width.%]="width()"></span>
        </span>
      </span>
    }
  `,
  styles: `
    .weekly-budget {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--cg-text-secondary);
    }

    .weekly-budget__text {
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    /* La barre est l'élément principal, pas une décoration : elle se lit d'un coup d'œil, et c'est
       ce que le PO a demandé — voir en permanence où en est le budget consommé. */
    .weekly-budget__gauge {
      display: inline-block;
      width: 120px;
      height: 8px;
      border-radius: 3px;
      background: var(--cg-surface-2);
      overflow: hidden;
    }

    .weekly-budget__fill {
      display: block;
      height: 100%;
      border-radius: 3px;
      background: var(--cg-success);
    }

    /* Les couleurs de l'alerte, pas d'autres : l'ambre d'encre à l'approche, le rouge au
       dépassement. Un écran qui inventerait ses propres teintes dirait un autre fait. */
    .weekly-budget--near .weekly-budget__fill { background: var(--cg-gold-ink); }
    .weekly-budget--near .weekly-budget__text { color: var(--cg-gold-ink); }
    .weekly-budget--over .weekly-budget__fill { background: var(--cg-error); }
    .weekly-budget--over .weekly-budget__text { color: var(--cg-error); }

    .weekly-budget--compact .weekly-budget__gauge { width: 64px; height: 6px; }
    .weekly-budget--compact .weekly-budget__text { font-size: 12px; }
  `,
})
export class WeeklyBudgetComponent {
  private readonly budgets = inject(WeeklyBudgetService);

  /** Le poste dont on montre le budget. */
  readonly hostId = input<string | null>(null);

  /** Version resserrée, pour la barre du terminal. */
  readonly compact = input(false);

  readonly line = computed(() => this.budgets.clientOf(this.hostId()));

  readonly level = computed<'ok' | 'near' | 'over'>(() => {
    const percent = this.line()?.percent ?? 0;
    if (percent >= 100) {
      return 'over';
    }
    return percent >= NEAR_PERCENT ? 'near' : 'ok';
  });

  /** La jauge ne dépasse jamais son cadre : au-delà, c'est la couleur qui parle. */
  readonly width = computed(() => Math.min(100, Math.max(0, this.line()?.percent ?? 0)));

  readonly label = computed(() => {
    const budget = this.line();
    if (!budget) {
      return '';
    }
    const spent = money(budget.spentEur);
    const ceiling = money(budget.budgetEur ?? 0);
    return this.compact() ? `${spent} / ${ceiling}` : `Cette semaine : ${spent} sur ${ceiling}`;
  });

  readonly title = computed(() => {
    const budget = this.line();
    if (!budget) {
      return '';
    }
    return `${budget.percent} % du budget hebdomadaire de ce client`;
  });
}

/** Les euros comme partout ailleurs dans F-133 : virgule française, deux décimales. */
function money(amount: number): string {
  return `${amount.toFixed(2).replace('.', ',')} €`;
}
