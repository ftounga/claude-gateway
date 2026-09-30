import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

import { SlashPanelHelpEntry } from './slash-panel-commands';

/**
 * Le **corps du panneau `/aide`** (F-165 / SF-165-01) : la liste des commandes slash disponibles.
 *
 * <p>Projeté dans le cadre réutilisable {@link AtelierSlashPanelComponent}. Il illustre le patron
 * d'extension : chaque commande a son composant de corps, sélectionné par `panelKind` dans le gabarit
 * du terminal. Composant de présentation pur — jetons `--cg-*` uniquement.</p>
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
}
