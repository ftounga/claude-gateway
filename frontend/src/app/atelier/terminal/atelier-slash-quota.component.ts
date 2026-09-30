import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { QuotaPanelState, ThreadQuotaSummary } from './slash-panel-commands';

/**
 * Le **corps du panneau `/quota`** (F-165 / SF-165-04) — la **consommation de tokens du plan**.
 *
 * <p>Il rend, sans aucun tour modèle : tokens utilisés vs plafond du plan (jauge + %), tokens restants,
 * et la date de reset (fin de période). La donnée vient de `GET /api/usage` (F-10, isolé `user_id`).</p>
 *
 * <p>Composant de **présentation pur** : il reçoit l'état et la donnée déjà lue et n'appelle rien. Jetons
 * `--cg-*` uniquement, chiffres en `tabular-nums`, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-quota',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-quota.component.html',
  styleUrl: './atelier-slash-quota.component.scss',
})
export class AtelierSlashQuotaComponent {
  /** État de la lecture : chargement, prête, ou en échec. */
  @Input() state: QuotaPanelState = 'loading';

  /** La consommation du plan, présente en état `ready`. */
  @Input() quota: ThreadQuotaSummary | null = null;

  /** Part du quota consommée, bornée à 100 % pour la jauge. */
  get usedPercent(): number {
    if (!this.quota || this.quota.quotaTokens <= 0) {
      return 0;
    }
    return Math.min(100, Math.round((this.quota.usedTokens / this.quota.quotaTokens) * 100));
  }

  /** Vrai quand le quota approche l'épuisement (≥ 80 %) : la jauge passe en teinte d'alerte. */
  get nearLimit(): boolean {
    return this.usedPercent >= 80;
  }

  /** Une date ISO `YYYY-MM-DD` en français (« 1 oct. 2026 »), ou la brute si non parsable. */
  date(iso: string): string {
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) {
      return iso;
    }
    return parsed.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  /** Un nombre entier en français (séparateur d'espace insécable pour les milliers). */
  count(value: number): string {
    return (value ?? 0).toLocaleString('fr-FR');
  }
}
