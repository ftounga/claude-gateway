import { Component, input, output } from '@angular/core';

import { RadarBrief } from '../../core/models/radar.models';
import { RadarBriefComponent } from './radar-brief.component';

/**
 * **L'onglet Radar** d'un client de la Vigie (F-102) : le résumé du matin (SF-102-01), puis les trois
 * colonnes et leurs gestes (SF-102-02). Il remplace l'état vide livré par F-106.
 */
@Component({
  selector: 'app-radar-board',
  imports: [RadarBriefComponent],
  template: `
    <div class="radar-board">
      <app-radar-brief [hostId]="hostId()" (briefChange)="briefChange.emit($event)"></app-radar-brief>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .radar-board {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-3);
    }
  `,
})
export class RadarBoardComponent {
  readonly hostId = input.required<string>();
  /** Le résumé relu : la Vigie en tire le compte de l'onglet. */
  readonly briefChange = output<RadarBrief>();
}
