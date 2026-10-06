import { Component, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { Hand, HandTarget } from './terminal-hand';

/**
 * **À qui la main, sous la saisie** (F-176 / SF-176-10, décision D7) : « À vous », « L'agent attend
 * votre validation ↑ » (un clic ramène la carte en vue), « L'agent travaille… ». Une seule règle,
 * dérivée de l'état du terminal (`handOf`) — jamais une supposition.
 */
@Component({
  selector: 'app-terminal-hand',
  imports: [MatIconModule],
  template: `
    @let h = hand();
    <p class="terminal-hand" [attr.data-state]="h.state" role="status" aria-live="polite">
      @switch (h.state) {
        @case ('working') {
          <mat-icon class="terminal-hand__icon" aria-hidden="true">hourglass_top</mat-icon>
        }
        @case ('awaiting') {
          <mat-icon class="terminal-hand__icon" aria-hidden="true">pan_tool</mat-icon>
        }
        @default {
          <mat-icon class="terminal-hand__icon" aria-hidden="true">edit</mat-icon>
        }
      }
      @if (h.target) {
        <button type="button" class="terminal-hand__anchor" (click)="reveal.emit(h.target)">
          {{ h.label }}
          <mat-icon class="terminal-hand__icon" aria-hidden="true">arrow_upward</mat-icon>
        </button>
      } @else {
        <span>{{ h.label }}</span>
      }
    </p>
  `,
  styles: `
    :host {
      display: block;
    }

    .terminal-hand {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin: var(--cg-space-1) 0 0;
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .terminal-hand[data-state='awaiting'] {
      color: var(--cg-orange-2);
      font-weight: 600;
    }

    /* Peau « Papier » du terminal Teams (F-89) : jetons AA de la surface claire (≥ 5:1). */
    :host-context(.terminal-view--teams) .terminal-hand {
      color: var(--cg-terminal-teams-muted);
    }

    :host-context(.terminal-view--teams) .terminal-hand[data-state='awaiting'] {
      color: var(--cg-terminal-teams-message);
    }

    .terminal-hand__icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
    }

    .terminal-hand__anchor {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
  `,
})
export class TerminalHandComponent {
  readonly hand = input.required<Hand>();

  /** Ramener en vue ce que l'agent attend. */
  readonly reveal = output<Exclude<HandTarget, null>>();
}
