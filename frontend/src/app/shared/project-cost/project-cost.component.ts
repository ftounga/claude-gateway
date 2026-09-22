import { Component, computed, inject, input } from '@angular/core';

import { ProjectCostService } from '../../core/services/project-cost.service';

/**
 * **Ce que ce projet a coûté** (F-143 / SF-143-01) — la semaine, et le total.
 *
 * <p><b>Deux montants, et pas un</b> : un projet à 2 € cette semaine peut en avoir coûté 300 depuis
 * mars. L'un sans l'autre ne permet pas d'arbitrer, et c'est bien d'arbitrer qu'il s'agit.</p>
 *
 * <p>Rien ne s'affiche pour qui n'est pas administrateur, ni si la lecture échoue : c'est un
 * indicateur, pas un service.</p>
 */
@Component({
  selector: 'app-project-cost',
  template: `
    @if (cost(); as spent) {
      <span class="project-cost" [class.project-cost--compact]="compact()" [title]="title()" role="status">
        <span class="project-cost__week num">{{ money(spent.weekEur) }}</span>
        <span class="project-cost__sep" aria-hidden="true">·</span>
        <span class="project-cost__total num">{{ money(spent.totalEur) }}</span>
        @if (!compact()) {
          <span class="project-cost__hint">cette semaine · au total</span>
        }
      </span>
    }
  `,
  styles: `
    .project-cost {
      display: inline-flex;
      align-items: baseline;
      gap: 6px;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .project-cost__week,
    .project-cost__total {
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    /* Le total porte l'engagement : c'est lui qu'on relit pour décider. */
    .project-cost__total {
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .project-cost__sep { color: var(--cg-divider); }

    .project-cost__hint { font-size: 11px; }

    .project-cost--compact { font-size: 11px; }
  `,
})
export class ProjectCostComponent {
  private readonly costs = inject(ProjectCostService);

  readonly projectId = input<string | null>(null);
  readonly compact = input(false);

  readonly cost = computed(() => this.costs.costOf(this.projectId()));

  readonly title = computed(() => {
    const spent = this.cost();
    if (!spent) {
      return '';
    }
    return `${money(spent.weekEur)} cette semaine (${spent.weekTurns} tour(s)), `
      + `${money(spent.totalEur)} depuis l'origine (${spent.totalTurns} tour(s))`;
  });

  money(amount: number): string {
    return money(amount);
  }
}

/** Les euros comme partout dans F-133 : virgule française, deux décimales. */
function money(amount: number): string {
  return `${amount.toFixed(2).replace('.', ',')} €`;
}
