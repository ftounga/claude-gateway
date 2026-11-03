import { Component, computed, input, output } from '@angular/core';

import { OpenChantier } from '../../core/models/journey.models';
import { laneStateOf } from '../lane-tabs';
import { PaneLayout, PaneSide, sideOf } from '../pane-layout';

/** Une case de la carte : un chantier ouvert, où il est, ce qu'il fait. */
export interface PaneMapEntry {
  number: number;
  title: string | null;
  side: PaneSide | null;
  state: string | null;
}

/** Les cases de la carte, par numéro croissant (fonction pure, testée). */
export function paneMapEntries(chantiers: readonly OpenChantier[], layout: PaneLayout,
                               active: readonly number[]): PaneMapEntry[] {
  return [...chantiers].sort((a, b) => a.number - b.number).map((c) => ({
    number: c.number,
    title: c.title,
    side: sideOf(layout, c.number),
    state: laneStateOf(c.number, chantiers, active),
  }));
}

/**
 * **La carte des volets** (F-246 / SF-246-01) — toujours visible dès que le sujet a deux chantiers ouverts : chaque
 * chantier, le volet qu'il occupe (« Gauche », « Droite ») ou aucun, ce qu'il fait (« tourne », « à vous »), et deux
 * boutons pour le mettre à gauche ou à droite. C'est le seul endroit d'où un chantier change de volet — avec
 * [Fermer ce volet] et l'ouverture d'un chantier à côté. Un chantier fermé se rouvre toujours d'ici.
 */
@Component({
  selector: 'app-terminal-pane-map',
  template: `
    <nav class="pane-map" aria-label="Carte des volets : quel chantier est où">
      <span class="pane-map__label">Volets</span>
      <ul class="pane-map__list">
        @for (entry of entries(); track entry.number) {
          <li class="pane-map__item" [class.pane-map__item--shown]="entry.side !== null"
              [attr.data-chantier]="entry.number">
            <span class="pane-map__name" [attr.title]="name(entry)">
              @if (entry.side) {
                <span class="pane-map__where">{{ entry.side === 'left' ? 'Gauche' : 'Droite' }}</span>
              }
              Ch.{{ entry.number }}@if (entry.title) {<span class="pane-map__title"> · {{ entry.title }}</span>}
            </span>
            @if (entry.state) {
              <span class="pane-map__state">{{ entry.state }}</span>
            }
            <span class="pane-map__moves">
              <button type="button" class="pane-map__move" [attr.data-chantier]="entry.number" data-side="left"
                      [attr.aria-pressed]="entry.side === 'left'"
                      [attr.aria-label]="'Afficher le chantier ' + entry.number + ' à gauche'"
                      (click)="placed.emit({ number: entry.number, side: 'left' })">◧</button>
              <button type="button" class="pane-map__move" [attr.data-chantier]="entry.number" data-side="right"
                      [attr.aria-pressed]="entry.side === 'right'"
                      [attr.aria-label]="'Afficher le chantier ' + entry.number + ' à droite'"
                      (click)="placed.emit({ number: entry.number, side: 'right' })">◨</button>
            </span>
          </li>
        }
      </ul>
    </nav>
  `,
  styleUrl: './terminal-pane-map.component.scss',
})
export class TerminalPaneMapComponent {
  readonly chantiers = input.required<readonly OpenChantier[]>();
  readonly layout = input.required<PaneLayout>();
  readonly activeLanes = input<readonly number[]>([]);

  /** « Mettre ce chantier à gauche / à droite ». */
  readonly placed = output<{ number: number; side: PaneSide }>();

  readonly entries = computed(() => paneMapEntries(this.chantiers(), this.layout(), this.activeLanes()));

  name(entry: PaneMapEntry): string {
    const where = entry.side === 'left' ? 'à gauche' : entry.side === 'right' ? 'à droite' : 'non affiché';
    return `Chantier ${entry.number}${entry.title ? ' · ' + entry.title : ''} — ${where}`;
  }
}
