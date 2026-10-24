import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Observable } from 'rxjs';

import {
  AppRequestStatus,
  AppRequestView,
  AtelierAppRequest,
  AtelierTerminalBlock,
} from '../../core/models/atelier.models';
import { AppRequestService, appRequestKindLabel, sizeLabel } from '../../core/services/app-request.service';

/**
 * **Le bloc de transcription d'un besoin reçu au fil de l'eau** (F-205 / SF-205-02). Rangé comme une
 * carte pour survivre au recalcul des blocs vivants.
 */
export function besoinBlock(toolUseId: string, besoin: AtelierAppRequest): AtelierTerminalBlock {
  return {
    tool: 'signaler_besoin',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    besoin,
  };
}

type CardState = 'loading' | AppRequestStatus;

/**
 * **La carte « Enregistrer ce besoin ? »** (F-205 / SF-205-02) : l'agent a reconnu un bug ou une
 * évolution **de l'application** ; rien n'entre au registre avant [Enregistrer]. [Non] l'écarte.
 */
@Component({
  selector: 'app-besoin-card',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="besoin-card" role="group" [attr.aria-label]="(complement() ? 'Compléter le besoin : ' : 'Besoin de l’application : ') + besoin().title">
      <mat-icon class="besoin-card__icon" aria-hidden="true">{{ besoin().kind === 'BUG' ? 'bug_report' : 'lightbulb' }}</mat-icon>
      <div class="besoin-card__body">
        @if (complement()) {
          <p class="besoin-card__headline">Compléter le besoin · {{ besoin().title }}</p>
        } @else {
          <p class="besoin-card__headline">{{ kind() }} de l’application · {{ besoin().title }}</p>
        }
        <blockquote class="besoin-card__words">{{ besoin().description }}</blockquote>
        @if (besoin().reformulation) {
          <p class="besoin-card__line"><span class="besoin-card__label">Compris :</span> {{ besoin().reformulation }}</p>
        }
        @if (besoin().observedContext) {
          <p class="besoin-card__line"><span class="besoin-card__label">Observé :</span> {{ besoin().observedContext }}</p>
        }
        @if (besoin().attachments.length) {
          <ul class="besoin-card__pieces" aria-label="Pièces jointes">
            @for (piece of besoin().attachments; track piece.position) {
              <li><mat-icon aria-hidden="true">attach_file</mat-icon>{{ piece.name }} <span class="besoin-card__size">{{ size(piece.size) }}</span></li>
            }
          </ul>
        }
        @switch (state()) {
          @case ('REJETE') {
            <p class="besoin-card__status">{{ complement() ? 'Écarté — le besoin d’origine est inchangé.' : 'Écarté — rien n’a été enregistré.' }}</p>
          }
          @case ('JOINT') {
            <p class="besoin-card__status besoin-card__status--recorded">Ajouté au besoin d’origine.</p>
          }
          @case ('PROPOSE') {
            @if (!readOnly()) {
              <div class="besoin-card__gestures">
                <button mat-flat-button type="button" class="besoin-card__record" [disabled]="busy()" (click)="record()">
                  {{ complement() ? 'Ajouter au besoin' : 'Enregistrer' }}
                </button>
                <button mat-button type="button" [disabled]="busy()" (click)="dismiss()">Non</button>
              </div>
            } @else {
              <p class="besoin-card__status">En attente de décision.</p>
            }
          }
          @case ('loading') {}
          @default {
            <p class="besoin-card__status besoin-card__status--recorded">Enregistré au registre des besoins.</p>
          }
        }
        @if (error()) {
          <p class="besoin-card__error" role="alert">{{ error() }}</p>
        }
      </div>
    </div>
  `,
  styles: `
    .besoin-card {
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

    .besoin-card__icon {
      color: var(--cg-orange-2);
      flex: none;
    }

    .besoin-card__body {
      min-width: 0;
      flex: 1;
    }

    .besoin-card__headline {
      margin: 0;
      font-weight: 600;
      color: var(--cg-orange-2);
      overflow-wrap: anywhere;
    }

    .besoin-card__words {
      margin: var(--cg-space-2) 0 0;
      padding: var(--cg-space-1) var(--cg-space-2);
      border-left: 2px solid var(--cg-divider);
      font-style: italic;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .besoin-card__line,
    .besoin-card__status {
      margin: var(--cg-space-1) 0 0;
      font-size: 13px;
      overflow-wrap: anywhere;
    }

    .besoin-card__label {
      font-weight: 600;
    }

    .besoin-card__pieces {
      margin: var(--cg-space-2) 0 0;
      padding: 0;
      list-style: none;
      font-size: 13px;
    }

    .besoin-card__pieces li {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      overflow-wrap: anywhere;
    }

    .besoin-card__pieces mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .besoin-card__size {
      opacity: 0.7;
    }

    .besoin-card__status--recorded {
      color: var(--cg-success);
      font-weight: 600;
    }

    .besoin-card__error {
      margin: var(--cg-space-1) 0 0;
      color: var(--cg-error);
      font-size: 13px;
    }

    .besoin-card__gestures {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .besoin-card__gestures button {
      min-height: 44px;
    }

    .besoin-card__record {
      --mdc-filled-button-container-color: var(--cg-orange-2);
      --mdc-filled-button-label-text-color: var(--cg-navy);
    }
  `,
})
export class BesoinCardComponent {
  private readonly requests = inject(AppRequestService);

  readonly besoin = input.required<AtelierAppRequest>();
  /** Le terminal du besoin (pour les appels) ; sans lui, la carte reste en lecture. */
  readonly workspaceId = input<string | null>(null);
  /** Lecture seule (mosaïque) : pas de bouton. */
  readonly readOnly = input(false);

  readonly state = signal<CardState>('loading');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly kind = computed(() => appRequestKindLabel(this.besoin().kind));
  /** F-205 / SF-205-04 : la carte propose un complément d'un besoin déjà enregistré. */
  readonly complement = computed(() => !!this.besoin().amendsRequestId);

  constructor() {
    // Le statut change après le tour : il se relit, il ne se fige pas dans la transcription.
    effect(() => {
      const ws = this.workspaceId();
      const id = this.besoin().requestId;
      if (!ws) {
        this.state.set('PROPOSE');
        return;
      }
      this.requests.get(ws, id).subscribe({
        next: (view) => this.state.set(view.status),
        error: () => this.state.set('PROPOSE'),
      });
    });
  }

  size(bytes: number): string {
    return sizeLabel(bytes);
  }

  record(): void {
    this.decide((ws, id) => this.requests.record(ws, id));
  }

  dismiss(): void {
    this.decide((ws, id) => this.requests.dismiss(ws, id));
  }

  private decide(call: (ws: string, id: string) => Observable<AppRequestView>): void {
    const ws = this.workspaceId();
    if (!ws || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    call(ws, this.besoin().requestId).subscribe({
      next: (view) => {
        this.state.set(view.status);
        this.busy.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? 'L’action n’a pas abouti : réessaie.');
        if (err?.status === 409) {
          // Déjà décidé ailleurs : on relit le statut réel.
          this.requests.get(ws, this.besoin().requestId).subscribe({
            next: (view) => this.state.set(view.status),
            error: () => undefined,
          });
        }
      },
    });
  }
}
