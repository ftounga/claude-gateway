import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { HttpErrorResponse } from '@angular/common/http';

import {
  AtelierGovernanceProposal,
  AtelierTerminalBlock,
} from '../../core/models/atelier.models';
import { GovernanceProposalService } from '../../core/services/governance-proposal.service';

/**
 * **Le bloc de transcription d'une proposition reçue au fil de l'eau** (F-177 / SF-177-02). Rangé comme
 * une carte pour survivre au recalcul des blocs vivants.
 */
export function proposalBlock(toolUseId: string, proposal: AtelierGovernanceProposal): AtelierTerminalBlock {
  return {
    tool: 'gouvernance_proposer',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    proposal,
  };
}

/** Libellé court du type proposé. */
export function proposalKindLabel(type: string): string {
  switch (type) {
    case 'SKILL':
      return 'Skill';
    case 'GABARIT':
      return 'Gabarit';
    default:
      return 'Règle';
  }
}

type CardState = 'loading' | 'PENDING' | 'APPLIED' | 'REFUSED' | 'unknown';

/**
 * **La carte de proposition de gouvernance** (F-177 / SF-177-02, décision D3) : ce que l'agent propose
 * d'écrire (règle, skill, gabarit), pour le poste ou le sujet, avec le diff. **Rien n'est écrit** avant
 * [Appliquer]. [Modifier] dépose une amorce dans la saisie (rien n'est envoyé) ; [Refuser] écarte.
 */
@Component({
  selector: 'app-proposal-card',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="proposal-card" role="group" [attr.aria-label]="'Proposition de gouvernance : ' + proposal().name">
      <mat-icon class="proposal-card__icon" aria-hidden="true">gavel</mat-icon>
      <div class="proposal-card__body">
        <p class="proposal-card__headline">
          {{ kind() }} proposé{{ proposal().type === 'REGLE' ? 'e' : '' }} · {{ proposal().name }}
        </p>
        <p class="proposal-card__where">
          {{ proposal().scope === 'POSTE' ? 'Pour tout le poste' : 'Pour ce sujet' }} ·
          <code>{{ proposal().path }}</code>{{ proposal().creates ? ' (nouveau fichier)' : '' }}
        </p>
        @if (proposal().reason) {
          <p class="proposal-card__reason">{{ proposal().reason }}</p>
        }
        <pre class="proposal-card__diff" aria-label="Modifications proposées">@for (line of proposal().diff; track $index) {<span [class]="'proposal-card__line proposal-card__line--' + line.kind.toLowerCase()">{{ marker(line.kind) }} {{ line.text }}
</span>}</pre>
        @if (proposal().omittedLines > 0) {
          <p class="proposal-card__omitted">… et {{ proposal().omittedLines }} ligne(s) de plus</p>
        }
        @switch (state()) {
          @case ('APPLIED') {
            <p class="proposal-card__status proposal-card__status--applied">Appliqué — vaut dès le prochain message.</p>
          }
          @case ('REFUSED') {
            <p class="proposal-card__status">Refusé — rien n'a été écrit.</p>
          }
          @case ('PENDING') {
            @if (!readOnly()) {
              <div class="proposal-card__gestures">
                <button mat-flat-button type="button" class="proposal-card__apply" [disabled]="busy()" (click)="apply()">
                  Appliquer
                </button>
                <button mat-stroked-button type="button" [disabled]="busy()" (click)="modify.emit(proposal().name)">
                  Modifier
                </button>
                <button mat-button type="button" [disabled]="busy()" (click)="refuse()">Refuser</button>
              </div>
            } @else {
              <p class="proposal-card__status">En attente de validation.</p>
            }
          }
        }
        @if (error()) {
          <p class="proposal-card__error" role="alert">{{ error() }}</p>
        }
      </div>
    </div>
  `,
  styles: `
    .proposal-card {
      display: flex;
      gap: var(--cg-space-2);
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-orange-2);
      border-left-width: 4px;
      border-radius: 8px;
      color: inherit;
      font-family: var(--cg-font-body);
    }

    .proposal-card__icon {
      color: var(--cg-orange-2);
      flex: none;
    }

    .proposal-card__body {
      min-width: 0;
      flex: 1;
    }

    .proposal-card__headline {
      margin: 0;
      font-weight: 600;
      color: var(--cg-orange-2);
    }

    .proposal-card__where,
    .proposal-card__reason,
    .proposal-card__omitted,
    .proposal-card__status {
      margin: var(--cg-space-1) 0 0;
      font-size: 13px;
      overflow-wrap: anywhere;
    }

    .proposal-card__reason {
      opacity: 0.85;
    }

    .proposal-card__diff {
      margin: var(--cg-space-2) 0 0;
      padding: var(--cg-space-2);
      max-height: 320px;
      overflow: auto;
      border-radius: 4px;
      border: 1px solid var(--cg-divider);
      font-family: var(--cg-font-mono);
      font-size: 12px;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .proposal-card__line--add {
      color: var(--cg-success);
    }

    .proposal-card__line--del {
      color: var(--cg-error);
      text-decoration: line-through;
    }

    .proposal-card__line--ctx {
      opacity: 0.6;
    }

    .proposal-card__status--applied {
      color: var(--cg-success);
      font-weight: 600;
    }

    .proposal-card__error {
      margin: var(--cg-space-1) 0 0;
      color: var(--cg-error);
      font-size: 13px;
    }

    .proposal-card__gestures {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .proposal-card__gestures button {
      min-height: 44px;
    }

    .proposal-card__apply {
      --mdc-filled-button-container-color: var(--cg-orange-2);
      --mdc-filled-button-label-text-color: var(--cg-navy);
    }
  `,
})
export class ProposalCardComponent {
  private readonly proposals = inject(GovernanceProposalService);

  readonly proposal = input.required<AtelierGovernanceProposal>();
  /** Le terminal de la proposition (pour les appels) ; sans lui, la carte reste en lecture. */
  readonly workspaceId = input<string | null>(null);
  /** Lecture seule (mosaïque) : pas de bouton. */
  readonly readOnly = input(false);

  /** [Modifier] : le nom de la proposition, pour l'amorce déposée dans la saisie. */
  readonly modify = output<string>();

  readonly state = signal<CardState>('loading');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly kind = computed(() => proposalKindLabel(this.proposal().type));

  constructor() {
    // Le statut change après le tour : il se relit, il ne se fige pas dans la transcription.
    effect(() => {
      const ws = this.workspaceId();
      const id = this.proposal().proposalId;
      if (!ws) {
        this.state.set('PENDING');
        return;
      }
      this.proposals.get(ws, id).subscribe({
        next: (view) => this.state.set(view.status),
        error: () => this.state.set('PENDING'),
      });
    });
  }

  marker(kind: string): string {
    return kind === 'ADD' ? '+' : kind === 'DEL' ? '−' : ' ';
  }

  apply(): void {
    this.decide((ws, id) => this.proposals.apply(ws, id));
  }

  refuse(): void {
    this.decide((ws, id) => this.proposals.refuse(ws, id));
  }

  private decide(
    call: (ws: string, id: string) => ReturnType<GovernanceProposalService['apply']>,
  ): void {
    const ws = this.workspaceId();
    if (!ws || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    call(ws, this.proposal().proposalId).subscribe({
      next: (view) => {
        this.state.set(view.status);
        this.busy.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? 'L’action n’a pas abouti : réessaie.');
        if (err?.status === 409) {
          // Déjà décidée ailleurs, ou fichier changé : on relit le statut réel.
          this.proposals.get(ws, this.proposal().proposalId).subscribe({
            next: (view) => this.state.set(view.status),
            error: () => undefined,
          });
        }
      },
    });
  }
}
