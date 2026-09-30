import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { ContextPanelState, ThreadContextSummary } from './slash-panel-commands';

/**
 * Le **corps du panneau `/contexte`** (F-165 / SF-165-03) — l'**état mémoire du fil**.
 *
 * <p>Il rend, en langage « classeur » : la taille du contexte vivant (en « pages »), la progression vers
 * le seuil de compaction (jauge), la part vivante vs rangée, la présence d'un résumé ancré, l'état de la
 * compaction et celui du rappel — le tout <b>sans aucun tour modèle</b>.</p>
 *
 * <p>Composant de **présentation pur** : il reçoit l'état et la donnée déjà lue par la gateway et
 * n'appelle rien. Jetons `--cg-*` uniquement, chiffres en `tabular-nums`, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-contexte',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-contexte.component.html',
  styleUrl: './atelier-slash-contexte.component.scss',
})
export class AtelierSlashContexteComponent {
  /** État de la lecture : chargement, prête, ou en échec. */
  @Input() state: ContextPanelState = 'loading';

  /** L'état mémoire du fil, présent en état `ready`. */
  @Input() context: ThreadContextSummary | null = null;

  /** Largeur de la jauge de remplissage, bornée à 100 % (jamais de débordement visuel). */
  get fillWidth(): number {
    return Math.min(100, Math.max(0, this.context?.fillPercent ?? 0));
  }

  /** Vrai quand le contexte approche le seuil (≥ 80 %) : la jauge passe en teinte d'alerte. */
  get nearThreshold(): boolean {
    return this.fillWidth >= 80;
  }

  /** Un nombre entier en français (séparateur d'espace insécable pour les milliers). */
  count(value: number): string {
    return (value ?? 0).toLocaleString('fr-FR');
  }
}
