import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { SujetPanelState, ThreadSujetSummary } from './slash-panel-commands';

/**
 * Le **corps du panneau `/sujet`** (F-165 / SF-165-05) — la **carte du projet courant** (sens F-141 :
 * un « sujet » = le dossier/projet courant).
 *
 * <p>Il compose ce que l'écran connaît déjà (nom, client, moteur) et ce que `GET /resume` fournit
 * (tours, mode, plan) — **sans aucun tour modèle**. Présentation pure : jetons `--cg-*`, ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-sujet',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-sujet.component.html',
  styleUrl: './atelier-slash-sujet.component.scss',
})
export class AtelierSlashSujetComponent {
  /** État de la lecture (`/resume`) : chargement, prête, ou en échec. */
  @Input() state: SujetPanelState = 'loading';

  /** La part chargée depuis `/resume` (tours, mode, plan), présente en état `ready`. */
  @Input() sujet: ThreadSujetSummary | null = null;

  /** Nom du projet (connu de l'écran). */
  @Input() name: string | null = null;

  /** Client / poste (connu de l'écran). */
  @Input() host: string | null = null;

  /** Moteur d'exécution : poste ou hébergé (connu de l'écran). */
  @Input() engine: string | null = null;

  /** Libellé lisible du moteur. */
  get engineLabel(): string {
    switch (this.engine) {
      case 'LOCAL_MACHINE':
        return 'Poste (machine)';
      case 'HOSTED_SANDBOX':
        return 'Hébergé';
      default:
        return '—';
    }
  }

  /** Libellé lisible du mode du fil. */
  get modeLabel(): string {
    switch (this.sujet?.mode) {
      case 'ANSWER_PLAN':
        return 'Plan';
      case 'ACT':
        return 'Agir';
      default:
        return 'Agir';
    }
  }

  /** Un nombre entier en français. */
  count(value: number): string {
    return (value ?? 0).toLocaleString('fr-FR');
  }
}
