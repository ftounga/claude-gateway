import { Component, input, output, viewChild } from '@angular/core';

import { RadarBrief } from '../../core/models/radar.models';
import { RadarBriefComponent } from './radar-brief.component';
import { RadarColumnsComponent } from './radar-columns.component';
import { RadarNewsComponent } from './radar-news.component';

/**
 * **L'onglet Radar** d'un client de la Vigie (F-102) : le résumé du matin (SF-102-01), puis les trois
 * colonnes et leurs gestes (SF-102-02). Il remplace l'état vide livré par F-106.
 *
 * <p>Les deux se tiennent : un geste dans une colonne relit le résumé (ses compteurs changent), et une
 * synchro terminée relit les colonnes.</p>
 */
@Component({
  selector: 'app-radar-board',
  imports: [RadarBriefComponent, RadarColumnsComponent, RadarNewsComponent],
  template: `
    <div class="radar-board">
      <app-radar-brief [hostId]="hostId()" (briefChange)="onBrief($event)"></app-radar-brief>
      <app-radar-columns [hostId]="hostId()" (changed)="onColumnsChanged()"></app-radar-columns>
      <!-- F-104 / SF-104-02 : Donner la nouvelle, en bas du Radar (maquette 1). -->
      <app-radar-news [hostId]="hostId()" (changed)="onNews()"></app-radar-news>
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

  private readonly brief = viewChild(RadarBriefComponent);
  private readonly columns = viewChild(RadarColumnsComponent);

  /** La synchro dont les colonnes sont le reflet : une autre qui se termine les fait relire. */
  private seenSync: { hostId: string; syncId: string | null } | null = null;

  onBrief(brief: RadarBrief): void {
    const syncId = brief.lastSync?.id ?? null;
    const previous = this.seenSync;
    this.seenSync = { hostId: this.hostId(), syncId };
    if (previous && previous.hostId === this.hostId() && previous.syncId !== syncId) {
      this.columns()?.load();
    }
    this.briefChange.emit(brief);
  }

  onColumnsChanged(): void {
    this.brief()?.load();
  }

  /** Une nouvelle écrite ou annulée : le résumé et les colonnes se relisent. */
  onNews(): void {
    this.brief()?.load();
    this.columns()?.load();
  }
}
