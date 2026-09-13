import { Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { HostProjectSummary } from '../../core/models/atelier.models';
import { HostPresenceService, elapsedLabel } from '../../core/services/host-presence.service';
import { LiveBadgeComponent } from '../../shared/live-badge/live-badge.component';
import { TerminalPreviewComponent } from '../../shared/terminal-preview/terminal-preview.component';
import { tileCenter } from '../forge-projects';

/**
 * **La tuile d'un projet** (F-98 / SF-98-03) — ce qui remplace la ligne empilée.
 *
 * <p>Un seul contenu central, par priorité : l'autorisation qui attend, sinon les dernières lignes,
 * sinon « au repos ». Une tuile qui montrerait tout redeviendrait la carte verticale qu'on retire.</p>
 *
 * <p><b>Présentationnelle</b> : elle ne lit rien à la gateway. L'aperçu et l'attente passent par
 * `app-terminal-preview`, composant unique du §12 ; la pastille de vie par `app-live-badge` (§11).
 * La date avance avec l'horloge partagée (F-97), sans appel.</p>
 */
@Component({
  selector: 'app-forge-project-tile',
  imports: [LiveBadgeComponent, MatButtonModule, MatIconModule, TerminalPreviewComponent],
  templateUrl: './forge-project-tile.component.html',
  styleUrl: './forge-project-tile.component.scss',
})
export class ForgeProjectTileComponent {
  private readonly presence = inject(HostPresenceService);

  readonly project = input.required<HostProjectSummary>();
  /** Le projet vit chez la gateway (F-71) : il n'a pas de chemin sous une racine. */
  readonly hosted = input(false);

  readonly open = output<HostProjectSummary>();

  readonly center = computed(() => tileCenter(this.project()));

  /** Chemin sous la racine — « la racine » quand il est vide. */
  readonly path = computed(() => {
    const path = this.project().projectPath;
    return path?.trim() ? path : 'la racine';
  });

  /** « Au repos · dernier tour il y a 5 min », daté à la seconde sans requête. */
  readonly idleLabel = computed(() => {
    const last = elapsedLabel(this.project().lastActivityAt, this.presence.now());
    return last ? `Au repos · dernier tour ${last}` : 'Au repos · aucun tour';
  });

  /** Ce que le projet a fait en dernier : le nom de l'outil, jamais sa cible. */
  readonly activity = computed(() => {
    const project = this.project();
    const last = elapsedLabel(project.lastActivityAt, this.presence.now());
    if (!last) {
      return null;
    }
    return project.lastTool ? `${project.lastTool} · ${last}` : last;
  });
}
