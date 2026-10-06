import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { AtelierSubjectHandoff, AtelierTerminalBlock } from '../../core/models/atelier.models';

/** Longueur de l'aperçu de la phrase sur la carte : la phrase entière part dans la saisie. */
export const HANDOFF_PREVIEW_CHARS = 180;

/**
 * **Le bloc de transcription d'une passation reçue au fil de l'eau** (F-179 / SF-179-02). Rangé comme
 * une carte (`withCards`) pour survivre au recalcul des blocs vivants.
 */
export function handoffBlock(toolUseId: string, handoff: AtelierSubjectHandoff): AtelierTerminalBlock {
  return {
    tool: 'ouvrir_sujet',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    handoff,
  };
}

/** L'aperçu de la phrase : coupée proprement, la coupe se dit. */
export function handoffPreview(phrase: string): string {
  const text = (phrase ?? '').trim();
  return text.length <= HANDOFF_PREVIEW_CHARS ? text : text.slice(0, HANDOFF_PREVIEW_CHARS).trimEnd() + '…';
}

/**
 * **La carte [Ouvrir le sujet]** (F-179 / SF-179-02, décision D3) : quand l'écran n'a pas ouvert le sujet
 * de lui-même (tour autonome, autre écran, rechargement, saisie en cours), la passation reste dans le fil.
 * Un clic ouvre le terminal du sujet avec la phrase **déposée** dans la saisie — jamais envoyée.
 */
@Component({
  selector: 'app-handoff-card',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="handoff-card" role="group" [attr.aria-label]="'Passation vers le sujet ' + handoff().name">
      <mat-icon class="handoff-card__icon" aria-hidden="true">forward</mat-icon>
      <div class="handoff-card__body">
        <p class="handoff-card__headline">Sujet prêt : {{ handoff().name }}</p>
        <p class="handoff-card__phrase">« {{ preview() }} »</p>
        @if (!readOnly()) {
          <div class="handoff-card__gestures">
            <button mat-flat-button type="button" class="handoff-card__open" (click)="open.emit(handoff())">
              Ouvrir le sujet
            </button>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .handoff-card {
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

    .handoff-card__icon {
      color: var(--cg-orange-2);
      flex: none;
    }

    .handoff-card__body {
      min-width: 0;
    }

    .handoff-card__headline {
      margin: 0;
      font-weight: 600;
      color: var(--cg-orange-2);
    }

    .handoff-card__phrase {
      margin: var(--cg-space-1) 0 0;
      overflow-wrap: anywhere;
      font-size: 13px;
      opacity: 0.85;
    }

    .handoff-card__gestures {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .handoff-card__open {
      --mdc-filled-button-container-color: var(--cg-orange-2);
      --mdc-filled-button-label-text-color: var(--cg-navy);
    }
  `,
})
export class HandoffCardComponent {
  readonly handoff = input.required<AtelierSubjectHandoff>();
  /** Lecture seule (mosaïque) : pas de bouton — la tuile offre son propre lien. */
  readonly readOnly = input(false);

  /** L'utilisateur veut ouvrir le sujet : le terminal entier s'en charge. */
  readonly open = output<AtelierSubjectHandoff>();

  readonly preview = computed(() => handoffPreview(this.handoff().phrase));
}

/**
 * **« Ouvert depuis le Terminal du poste · [Revenir] »** (F-179 / SF-179-02, D2) : une ligne sous l'en-tête
 * du terminal du sujet ouvert par une passation. Dans le flux de la colonne : rien ne couvre la saisie.
 */
@Component({
  selector: 'app-handoff-origin',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="handoff-origin" role="status">
      <mat-icon class="handoff-origin__icon" aria-hidden="true">forward</mat-icon>
      <span class="handoff-origin__text">Ouvert depuis {{ fromName() }} · la phrase est prête dans la saisie, à toi de l'envoyer</span>
      <button mat-button type="button" class="handoff-origin__back" (click)="back.emit()">Revenir</button>
    </div>
  `,
  styles: `
    .handoff-origin {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border-bottom: 1px solid var(--cg-orange-2);
      color: var(--cg-surface);
      font-size: 13px;
    }

    .handoff-origin__icon {
      flex: none;
      color: var(--cg-orange-2);
    }

    .handoff-origin__text {
      flex: 1;
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .handoff-origin__back {
      flex: none;
      --mdc-text-button-label-text-color: var(--cg-orange-2);
    }
  `,
})
export class HandoffOriginComponent {
  readonly fromName = input.required<string>();
  readonly back = output<void>();
}
