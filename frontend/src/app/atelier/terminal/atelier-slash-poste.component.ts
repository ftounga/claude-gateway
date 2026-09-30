import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { PostePanelState, ThreadPosteSummary } from './slash-panel-commands';

/**
 * Le **corps du panneau `/poste`** (F-165 / SF-165-05) — l'**état du poste** (runner).
 *
 * <p>Il rend, sans aucun tour modèle : connecté / hors ligne + « vu il y a X », OS, shell, racine,
 * élévation. La donnée vient de `GET /api/runner-hosts` (existant, isolé `user_id`).</p>
 *
 * <p>Composant de **présentation pur** : il reçoit l'état et la donnée et n'appelle rien. Jetons
 * `--cg-*` uniquement, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-poste',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-poste.component.html',
  styleUrl: './atelier-slash-poste.component.scss',
})
export class AtelierSlashPosteComponent {
  /** État de la lecture : chargement, prête, « pas de poste », ou en échec. */
  @Input() state: PostePanelState = 'loading';

  /** L'état du poste, présent en état `ready`. */
  @Input() poste: ThreadPosteSummary | null = null;

  /** Libellé lisible du shell, ou tiret. */
  get shellLabel(): string {
    switch (this.poste?.shell) {
      case 'posix':
        return 'POSIX (bash/zsh)';
      case 'powershell':
        return 'PowerShell';
      case 'cmd':
        return 'CMD';
      default:
        return '—';
    }
  }

  /** « vu il y a X » à partir du dernier battement, ou vide si inconnu. */
  get seenAgo(): string {
    const iso = this.poste?.lastSeenAt;
    if (!iso) {
      return '';
    }
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) {
      return '';
    }
    const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
    if (seconds < 60) {
      return `vu il y a ${seconds} s`;
    }
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) {
      return `vu il y a ${minutes} min`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return `vu il y a ${hours} h`;
    }
    return `vu il y a ${Math.round(hours / 24)} j`;
  }
}
