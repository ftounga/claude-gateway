import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { JOURNEY_PHASES, SubjectJourney } from '../../core/models/journey.models';

/** Un geste de l'utilisateur sur le parcours, rendu au terminal qui appelle la gateway. */
export type JourneyGesture = 'accept-guided' | 'decline-guided';

/**
 * **Le parcours du sujet, au-dessus de la saisie** (F-176) :
 * - en Libre, rien — sauf la carte **[Passer en guidé] [Rester libre]** quand l'agent a qualifié la
 *   demande de chantier (SF-176-02) ;
 * - en Guidé, les phases du sujet, la phase courante marquée (SF-176-01).
 *
 * <p>Relu à chaque fin de tour : une proposition faite pendant le tour apparaît dès qu'il se termine.</p>
 */
@Component({
  selector: 'app-terminal-journey-strip',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (proposal(); as p) {
      <section class="journey-card" role="region" aria-label="Proposition de passer en mode guidé">
        <mat-icon class="journey-card__icon" aria-hidden="true">route</mat-icon>
        <div class="journey-card__body">
          <strong>Ce sujet ressemble à un chantier.</strong>
          @if (p.reason) {
            <span class="journey-card__reason">{{ p.reason }}</span>
          }
          <span class="journey-card__hint">En mode guidé : on comprend, on planifie, vous validez le plan, puis on agit.</span>
        </div>
        <div class="journey-card__actions">
          <button mat-flat-button type="button" class="journey-card__accept" [disabled]="busy()" (click)="gesture.emit('accept-guided')">Passer en guidé</button>
          <button mat-button type="button" class="journey-card__decline" [disabled]="busy()" (click)="gesture.emit('decline-guided')">Rester libre</button>
        </div>
      </section>
    }
    @if (guided()) {
      <section class="journey-strip" aria-label="Parcours guidé du sujet">
        <ol class="journey-steps">
          @for (step of phases; track step.phase; let i = $index) {
            <li
              class="journey-step"
              [class.journey-step--done]="i < currentIndex()"
              [class.journey-step--current]="i === currentIndex()"
              [attr.aria-current]="i === currentIndex() ? 'step' : null"
            >
              @if (i < currentIndex()) {
                <mat-icon class="journey-step__icon" aria-hidden="true">check</mat-icon>
              }
              {{ step.label }}
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .journey-strip,
    .journey-card {
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      font-size: 13px;
    }

    .journey-card {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2) var(--cg-space-3);
      border-color: var(--cg-orange-2);
    }

    .journey-card__icon {
      color: var(--cg-orange-2);
    }

    .journey-card__body {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-width: 12rem;
      gap: 2px;
    }

    .journey-card__hint {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-card__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
    }

    .journey-steps {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1) var(--cg-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .journey-step {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      color: var(--cg-text-secondary);
    }

    .journey-step + .journey-step::before {
      content: '→';
      margin-right: var(--cg-space-2);
      color: var(--cg-text-secondary);
    }

    .journey-step--done {
      color: var(--cg-success);
    }

    .journey-step--current {
      color: var(--cg-orange-2);
      font-weight: 600;
    }

    .journey-step__icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }
  `,
})
export class TerminalJourneyStripComponent {
  readonly journey = input<SubjectJourney | null>(null);

  /** Un geste est en cours : les boutons attendent. */
  readonly busy = input(false);

  /** Le geste de l'utilisateur ; le terminal appelle la gateway. */
  readonly gesture = output<JourneyGesture>();

  readonly phases = JOURNEY_PHASES;

  readonly guided = computed(() => {
    const j = this.journey();
    return !!j && j.mode === 'GUIDE' && !!j.phase;
  });

  /** La proposition du mode guidé qui attend un geste — seulement en Libre. */
  readonly proposal = computed(() => {
    const j = this.journey();
    return j && j.mode !== 'GUIDE' ? j.guidedProposal ?? null : null;
  });

  readonly currentIndex = computed(() => {
    const phase = this.journey()?.phase;
    return phase ? JOURNEY_PHASES.findIndex(p => p.phase === phase) : -1;
  });
}
