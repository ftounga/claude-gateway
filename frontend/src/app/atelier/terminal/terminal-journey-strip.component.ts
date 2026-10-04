import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { JOURNEY_PHASES, SubjectJourney } from '../../core/models/journey.models';

/**
 * **Le parcours guidé, au-dessus de la saisie** (F-176) : les phases du sujet, la phase courante
 * marquée. Absent en mode Libre (décision Q4 : rien ne change).
 */
@Component({
  selector: 'app-terminal-journey-strip',
  imports: [MatIconModule],
  template: `
    @if (visible()) {
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

    .journey-strip {
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      font-size: 13px;
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

  readonly phases = JOURNEY_PHASES;

  readonly visible = computed(() => {
    const j = this.journey();
    return !!j && j.mode === 'GUIDE' && !!j.phase;
  });

  readonly currentIndex = computed(() => {
    const phase = this.journey()?.phase;
    return phase ? JOURNEY_PHASES.findIndex(p => p.phase === phase) : -1;
  });
}
