import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { TerminalActivity, TerminalPreview } from '../../core/models/atelier.models';

/** Densité d'affichage : trois lignes sur une carte, six dans une tuile de supervision. */
export type TerminalPreviewDensity = 'card' | 'tile';

/** Lignes montrées selon la densité. Même source, deux tailles — jamais deux vérités. */
const LINES_BY_DENSITY: Record<TerminalPreviewDensity, number> = { card: 3, tile: 6 };

/**
 * **L'aperçu vivant d'un terminal** (F-76 / SF-76-02) : ce qu'il fait, écrit, et ses dernières
 * lignes.
 *
 * <p><b>Un composant, deux densités.</b> Le PO a demandé la même chose à deux endroits — quelques
 * lignes sous le nom d'un projet sur l'accueil de la Forge, l'aperçu complet dans une tuile de
 * supervision. Deux composants divergeraient au premier ajustement ; celui-ci ne change que le
 * nombre de lignes qu'il montre.</p>
 *
 * <p><b>Aucun registre de couleur nouveau.</b> Trois cohabitent déjà (§5 statut, §9 identité du
 * poste, §10 état de mission) et un quatrième les rendrait tous illisibles. L'aperçu est en encre
 * secondaire, et l'attente d'autorisation emprunte la pastille <b>§5 « En attente »</b> — celle qui
 * sert déjà, dans toute l'application, à dire qu'on attend quelque chose.</p>
 *
 * <p><b>Ce qui attend une autorisation se dit franchement</b>, et se dit <b>en toutes lettres</b> :
 * le 2026-09-08, une demande d'autorisation est restée douze heures sans réponse (F-47). Un point
 * de couleur n'aurait pas suffi ce jour-là et ne suffira pas davantage demain — d'où une pastille
 * qui <b>écrit</b> « Attend votre autorisation », et qu'aucune entrée ne permet de réduire au
 * silence.</p>
 *
 * <p>Composant de <b>présentation</b> : aucun appel réseau, aucune écriture. Écrire dans un aperçu
 * est hors périmètre — c'est le terminal qui reçoit ce qu'on tape, et il est à un clic.</p>
 */
@Component({
  selector: 'app-terminal-preview',
  imports: [MatIconModule],
  templateUrl: './terminal-preview.component.html',
  styleUrl: './terminal-preview.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TerminalPreviewComponent {

  private readonly previewValue = signal<TerminalPreview | null>(null);
  private readonly densityValue = signal<TerminalPreviewDensity>('card');

  /** L'aperçu à montrer. `null` ⇒ le composant ne rend rien. */
  @Input()
  set preview(value: TerminalPreview | null | undefined) {
    this.previewValue.set(value ?? null);
  }

  /** `card` (accueil de la Forge) ou `tile` (vue de supervision). */
  @Input()
  set density(value: TerminalPreviewDensity | null | undefined) {
    this.densityValue.set(value === 'tile' ? 'tile' : 'card');
  }

  /**
   * Vrai quand il y a quelque chose à montrer. Un aperçu inactif et sans ligne n'apprendrait rien
   * de plus que la pastille de vie déjà présente depuis F-70 : on n'affiche alors rien du tout.
   */
  readonly visible = computed(() => {
    const preview = this.previewValue();
    if (!preview) {
      return false;
    }
    return preview.activity !== 'IDLE' || (preview.lines?.length ?? 0) > 0;
  });

  /** Vrai pour le seul état qui réclame une décision — celui qu'on doit voir. */
  readonly awaiting = computed(() => this.previewValue()?.activity === 'AWAITING_APPROVAL');

  /** Ce qui est en cours (« npm test »), quand il y a quelque chose à nommer. */
  readonly detail = computed(() => {
    const value = this.previewValue()?.activityDetail?.trim();
    return value && value.length > 0 ? value : null;
  });

  /** Les dernières lignes, coupées à ce que la densité peut montrer. */
  readonly lines = computed(() => {
    const preview = this.previewValue();
    const lines = preview?.lines ?? [];
    return lines.slice(-LINES_BY_DENSITY[this.densityValue()]);
  });

  /** Ce qui se passe, **écrit**. Jamais une couleur seule, jamais une icône seule. */
  readonly activityLabel = computed(() => {
    const preview = this.previewValue();
    if (!preview) {
      return '';
    }
    const detail = preview.activityDetail?.trim();
    switch (preview.activity as TerminalActivity) {
      case 'AWAITING_APPROVAL':
        return detail ? `Attend votre autorisation — ${detail}` : 'Attend votre autorisation';
      case 'RUNNING':
        return detail ? `Exécute ${detail}` : 'Exécute une commande';
      case 'THINKING':
        return 'Réfléchit';
      default:
        return 'Inactif';
    }
  });
}
