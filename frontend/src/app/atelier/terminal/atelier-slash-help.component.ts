import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

import { SlashPanelHelpEntry } from './slash-panel-commands';

/** Une famille de commandes pour l'affichage groupé de `/aide`. */
interface HelpGroup {
  readonly label: string;
  readonly entries: readonly SlashPanelHelpEntry[];
}

/**
 * Le **corps du panneau `/aide`** (F-165 / SF-165-01, complété SF-165-06) : la liste **complète** des
 * commandes slash disponibles, **groupées par famille** (Vues / Actions / Aide).
 *
 * <p>Projeté dans le cadre réutilisable {@link AtelierSlashPanelComponent}. La liste vient du registre
 * (source de vérité) ; le regroupement n'est qu'un rendu. Composant de présentation pur — jetons
 * `--cg-*` uniquement.</p>
 */
@Component({
  selector: 'app-atelier-slash-help',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-help.component.html',
  styleUrl: './atelier-slash-help.component.scss',
})
export class AtelierSlashHelpComponent {
  /** Les commandes à lister (nom, famille, description). */
  @Input() entries: readonly SlashPanelHelpEntry[] = [];

  /** Ordre d'affichage des familles ; toute famille non listée vient ensuite, dans son ordre d'apparition. */
  private static readonly FAMILY_ORDER = ['Vue', 'Action', 'Aide'];

  /** Les commandes regroupées par famille, dans l'ordre `Vue → Action → Aide`. */
  get groups(): HelpGroup[] {
    const byLabel = new Map<string, SlashPanelHelpEntry[]>();
    for (const entry of this.entries) {
      const bucket = byLabel.get(entry.kindLabel) ?? [];
      bucket.push(entry);
      byLabel.set(entry.kindLabel, bucket);
    }
    const labels = [...byLabel.keys()].sort((a, b) => {
      const ia = AtelierSlashHelpComponent.FAMILY_ORDER.indexOf(a);
      const ib = AtelierSlashHelpComponent.FAMILY_ORDER.indexOf(b);
      return (ia === -1 ? Number.MAX_SAFE_INTEGER : ia) - (ib === -1 ? Number.MAX_SAFE_INTEGER : ib);
    });
    return labels.map((label) => ({ label, entries: byLabel.get(label) ?? [] }));
  }
}
