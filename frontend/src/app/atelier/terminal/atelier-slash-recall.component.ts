import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RecallPanelState, ThreadRecallResult } from './slash-panel-commands';

/**
 * Le **corps du panneau `/rappel`** (F-165 / SF-165-06) — les **extraits** retrouvés dans l'historique.
 *
 * <p>Il rend, sans aucun tour modèle : le terme cherché, s'il a répondu par le sens (sémantique) ou par
 * mot-clé, et la liste des extraits (rôle, extrait borné, date). La donnée vient de
 * `GET /api/workspaces/{id}/chat/recall` (réutilise la recherche F-162, isolée `user_id`).</p>
 *
 * <p>Composant de **présentation pur** : jetons `--cg-*`, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-recall',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-recall.component.html',
  styleUrl: './atelier-slash-recall.component.scss',
})
export class AtelierSlashRecallComponent {
  /** État de la lecture : chargement, prête, vide (pas de terme), ou en échec. */
  @Input() state: RecallPanelState = 'loading';

  /** Le terme saisi (affiché même pendant le chargement). */
  @Input() term = '';

  /** Le résultat du rappel, présent en état `ready`. */
  @Input() result: ThreadRecallResult | null = null;

  /** Libellé lisible du rôle d'un extrait. */
  roleLabel(role: string): string {
    const value = (role ?? '').toLowerCase();
    if (value === 'user') {
      return 'Vous';
    }
    if (value === 'assistant') {
      return 'Claude';
    }
    return role ?? '';
  }

  /** Une date ISO en français court (« 30 sept. 2026 »), ou la brute si non parsable. */
  date(iso: string): string {
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) {
      return iso;
    }
    return parsed.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
  }
}
