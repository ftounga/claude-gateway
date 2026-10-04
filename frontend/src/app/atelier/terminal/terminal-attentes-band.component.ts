import { Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { TerminalActionBoard } from '../../core/models/terminal-actions.models';

/** « 9 j », « aujourd'hui » : l'âge de la plus ancienne attente ouverte. */
export function oldestLabel(oldestOpenAt: string | null, now = Date.now()): string | null {
  if (!oldestOpenAt) {
    return null;
  }
  const started = Date.parse(oldestOpenAt);
  if (Number.isNaN(started)) {
    return null;
  }
  const days = Math.floor(Math.max(0, now - started) / 86_400_000);
  return days <= 0 ? "aujourd'hui" : `${days} j`;
}

/** Le résumé de la bande : « 2 à faire · 4 demandées · la plus ancienne 9 j ». */
export function bandSummary(board: TerminalActionBoard | null, now = Date.now()): string {
  if (!board) {
    return '';
  }
  const parts: string[] = [];
  if (board.aFaire > 0) {
    parts.push(`${board.aFaire} à faire`);
  }
  if (board.demande > 0) {
    parts.push(board.demande === 1 ? '1 demandée' : `${board.demande} demandées`);
  }
  if ((board.aRelancer ?? 0) > 0) {
    parts.push(`${board.aRelancer} à relancer`);
  }
  const oldest = oldestLabel(board.oldestOpenAt, now);
  if (oldest && parts.length) {
    parts.push(`la plus ancienne ${oldest}`);
  }
  return parts.join(' · ');
}

/** Combien de fermetures proposées par l'agent attendent un geste, sur tout le tableau. */
export function pendingProposals(board: TerminalActionBoard | null): number {
  if (!board) {
    return 0;
  }
  return [...board.here, ...board.host]
    .filter(a => !!a.proposedStatus && (a.status === 'A_FAIRE' || a.status === 'DEMANDE')).length;
}

/**
 * **La bande des attentes** (F-175 / SF-175-04, décision D6) : au-dessus de la zone de saisie, dès
 * qu'il y a au moins une attente ouverte sur le poste. On ne peut plus la louper — c'était le défaut
 * de la pastille de F-154, qui disparaissait à zéro et ne se relisait qu'au changement de terminal.
 *
 * <p>Un clic ouvre le panneau. Rien d'autre : la bande annonce, le panneau agit.</p>
 */
@Component({
  selector: 'app-terminal-attentes-band',
  imports: [MatIconModule],
  template: `
    @if (visible()) {
      <button type="button" class="attentes-band" (click)="open.emit()"
        [attr.aria-label]="'Attentes : ' + summary() + (proposals() ? ', ' + proposals() + ' à confirmer' : '') + '. Ouvrir le panneau.'">
        <mat-icon class="attentes-band__icon" aria-hidden="true">pending_actions</mat-icon>
        <span class="attentes-band__label">Attentes</span>
        <span class="attentes-band__summary">{{ summary() }}</span>
        @if (proposals()) {
          <span class="attentes-band__confirm">{{ proposals() }} à confirmer</span>
        }
        @if (toReview() > 0) {
          <span class="attentes-band__confirm attentes-band__review">{{ toReview() }} à vérifier</span>
        }
        <mat-icon class="attentes-band__chevron" aria-hidden="true">chevron_right</mat-icon>
      </button>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .attentes-band {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      width: 100%;
      box-sizing: border-box;
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-orange-2);
      border-radius: 8px;
      background: transparent;
      color: var(--cg-orange-2);
      font: inherit;
      font-size: 13px;
      text-align: left;
      cursor: pointer;
    }

    .attentes-band:hover,
    .attentes-band:focus-visible {
      background: color-mix(in srgb, var(--cg-orange-2) 12%, transparent);
      outline: none;
    }

    .attentes-band__icon,
    .attentes-band__chevron {
      font-size: 18px;
      width: 18px;
      height: 18px;
    }

    .attentes-band__label {
      font-weight: 600;
    }

    .attentes-band__summary {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .attentes-band__confirm {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-orange-2);
      color: var(--cg-navy);
      font-weight: 600;
      white-space: nowrap;
    }
  `,
})
export class TerminalAttentesBandComponent {
  /** Le tableau du terminal (compteurs du poste). `null` : rien de chargé, rien d'affiché. */
  readonly board = input<TerminalActionBoard | null>(null);

  /** Attentes héritées encore à vérifier (F-175 / SF-175-07). */
  readonly toReview = input(0);

  /** Ouvrir le panneau. */
  readonly open = output<void>();

  readonly summary = computed(() => bandSummary(this.board()));
  readonly proposals = computed(() => pendingProposals(this.board()));
  readonly visible = computed(() => {
    const b = this.board();
    return !!b && b.aFaire + b.demande > 0;
  });
}
