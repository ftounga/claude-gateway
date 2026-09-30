import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { CostPanelState, ThreadCostBudget, ThreadCostSummary } from './slash-panel-commands';

/** Un segment de la décomposition, prêt à rendre (barre + légende). */
interface CostSegment {
  readonly label: string;
  readonly icon: string;
  readonly eur: number;
  readonly percent: number;
  readonly cssClass: string;
}

/** Un point de la mini-tendance, mis à l'échelle du plus cher (hauteur relative en %). */
interface TrendBar {
  readonly heightPercent: number;
  readonly eur: number;
}

/**
 * Le **corps du panneau `/cout`** (F-165 / SF-165-02) — le **vaisseau amiral** de la feature.
 *
 * <p>Il rend l'**économie du fil courant** en langage « classeur » : coût cumulé et du dernier tour,
 * décomposition écriture / lecture / sortie en mini-barres, part de cache chaud, taille du contexte
 * vivant (en « pages »), part rangée vs vivante, budget restant si lisible, et une mini-tendance.</p>
 *
 * <p>Composant de **présentation pur** : il reçoit l'état et la donnée déjà lue par la gateway et
 * n'appelle rien. Jetons `--cg-*` uniquement, chiffres en `tabular-nums`, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-cost',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-cost.component.html',
  styleUrl: './atelier-slash-cost.component.scss',
})
export class AtelierSlashCostComponent {
  /** État de la lecture : chargement, prête, ou en échec. */
  @Input() state: CostPanelState = 'loading';

  /** L'économie du fil, présente en état `ready`. */
  @Input() cost: ThreadCostSummary | null = null;

  /** Le budget applicable, s'il est déjà lisible (sinon la ligne est absente). */
  @Input() budget: ThreadCostBudget | null = null;

  /** Les trois postes de la décomposition, prêts à rendre. */
  get segments(): CostSegment[] {
    const breakdown = this.cost?.breakdown;
    if (!breakdown) {
      return [];
    }
    return [
      {
        label: 'Écriture cache',
        icon: '✍️',
        eur: breakdown.writeEur,
        percent: breakdown.writePercent,
        cssClass: 'is-write',
      },
      {
        label: 'Lecture cache',
        icon: '📖',
        eur: breakdown.readEur,
        percent: breakdown.readPercent,
        cssClass: 'is-read',
      },
      {
        label: 'Sortie',
        icon: '💬',
        eur: breakdown.outputEur,
        percent: breakdown.outputPercent,
        cssClass: 'is-output',
      },
    ];
  }

  /** Vrai quand au moins un poste a une part : sinon la barre segmentée reste vide (fil neuf). */
  get hasBreakdown(): boolean {
    return this.segments.some((segment) => segment.percent > 0);
  }

  /** La mini-tendance mise à l'échelle du tour le plus cher (barres relatives). */
  get trend(): TrendBar[] {
    const values = this.cost?.trendEur ?? [];
    const max = values.reduce((peak, value) => Math.max(peak, value), 0);
    return values.map((eur) => ({
      eur,
      // Un plancher visible pour un tour non nul, même minuscule ; 0 reste 0.
      heightPercent: max <= 0 ? 0 : Math.max(eur > 0 ? 8 : 0, Math.round((eur / max) * 100)),
    }));
  }

  /** Vrai dès qu'il y a une tendance à montrer. */
  get hasTrend(): boolean {
    return (this.cost?.trendEur?.length ?? 0) > 0;
  }

  /** Budget restant de la semaine (jamais négatif à l'affichage). */
  get budgetRemaining(): number {
    if (!this.budget) {
      return 0;
    }
    return Math.max(0, this.budget.budgetEur - this.budget.spentEur);
  }

  /** Part du budget consommée, bornée à 100 % pour la barre. */
  get budgetPercent(): number {
    if (!this.budget || this.budget.budgetEur <= 0) {
      return 0;
    }
    return Math.min(100, Math.round((this.budget.spentEur / this.budget.budgetEur) * 100));
  }

  /** Un montant dans la devise du fil, en français : « 1,23 € ». */
  money(value: number): string {
    const amount = (value ?? 0).toFixed(2).replace('.', ',');
    return this.cost?.currency === 'EUR' ? `${amount} €` : `${amount} ${this.cost?.currency ?? ''}`.trim();
  }

  /** Un nombre entier en français (séparateur d'espace insécable pour les milliers). */
  count(value: number): string {
    return (value ?? 0).toLocaleString('fr-FR');
  }
}
