import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { SlashPanel, slashPanelKindLabel } from './slash-panel-commands';

/**
 * Le **cadre de panneau réutilisable** des commandes slash F-165 (F-165 / SF-165-01).
 *
 * <p>Composant de **présentation** : il rend le CHROME commun d'un panneau — en-tête (icône, titre,
 * jeton `/nom`, badge de famille, bouton fermer) — et **projette** le corps spécifique à la commande
 * (`<ng-content>`). C'est le cadre que les SF-165-02 → 06 réutilisent : chacune fournit son corps.</p>
 *
 * <p>Le panneau est de l'**affichage local** : il ne vit que dans le fil du terminal, jamais dans
 * l'historique envoyé au modèle. Aucun `--cg-*` hors charte, cibles tactiles ≥ 44 px (SF-158).</p>
 */
@Component({
  selector: 'app-atelier-slash-panel',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-panel.component.html',
  styleUrl: './atelier-slash-panel.component.scss',
})
export class AtelierSlashPanelComponent {
  /** Le panneau à encadrer (en-tête). Le corps est projeté par le parent. */
  @Input({ required: true }) panel!: SlashPanel;

  /** Fermeture demandée par l'utilisateur (bouton ×). Le parent retire le panneau du fil. */
  @Output() dismiss = new EventEmitter<void>();

  /** Libellé de la famille pour le badge (« Vue » / « Action » / « Aide »). */
  get kindLabel(): string {
    return slashPanelKindLabel(this.panel.kind);
  }
}
