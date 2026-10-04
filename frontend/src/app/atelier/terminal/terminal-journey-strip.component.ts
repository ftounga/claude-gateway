import { Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
  JOURNEY_PHASES, SubjectJourney, stepStatusLabel, waitsOnLabel,
} from '../../core/models/journey.models';

/** Un geste de l'utilisateur sur le parcours, rendu au terminal qui appelle la gateway. */
export type JourneyGesture = 'accept-guided' | 'decline-guided' | 'validate-plan';

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
        @if (waitingInputs() > 0) {
          <span class="journey-waiting">en attente de {{ waitingInputs() }} input{{ waitingInputs() > 1 ? 's' : '' }}</span>
        }
        @if (plan(); as p) {
          <div class="journey-plan">
            <button type="button" class="journey-plan__toggle" [attr.aria-expanded]="planOpen()" (click)="togglePlan()">
              <mat-icon aria-hidden="true">{{ planOpen() ? 'expand_less' : 'expand_more' }}</mat-icon>
              Plan v{{ p.version }} · {{ p.steps.length }} étape{{ p.steps.length > 1 ? 's' : '' }}
              @if (p.awaitingValidation) {
                <span class="journey-plan__await">{{ p.amendment ? 'amendement à valider' : 'à valider' }}</span>
              } @else if (p.validatedVersion) {
                <span class="journey-plan__ok">validé</span>
              }
            </button>
            @if (planOpen()) {
              <ol class="journey-plan__steps">
                @for (step of p.steps; track $index) {
                  <li class="journey-plan__step" [class.journey-plan__step--changed]="step.changed">
                    <div class="journey-plan__line">
                      <span class="journey-plan__status" [attr.data-status]="step.status">{{ statusLabel(step.status) }}</span>
                      <span class="journey-plan__title">{{ step.title }}</span>
                      <span class="journey-plan__risk" [attr.data-risk]="step.risk">{{ step.riskLabel }}</span>
                      @if (step.changed) {
                        <span class="journey-plan__changed">modifiée</span>
                      }
                    </div>
                    @if (step.verify) {
                      <div class="journey-plan__detail">Vérifier : {{ step.verify }}</div>
                    }
                    @if (step.rollback) {
                      <div class="journey-plan__detail">Retour arrière : {{ step.rollback }}</div>
                    }
                    @if (step.waitsOn) {
                      <div class="journey-plan__detail">Attend : {{ step.waitsOn }} — {{ waitsLabel(step.waitsOnStatus) }}</div>
                    }
                    @if (step.evidence) {
                      <div class="journey-plan__detail">Preuve : {{ step.evidence }}</div>
                    }
                  </li>
                }
              </ol>
            }
            @if (p.awaitingValidation) {
              <div class="journey-plan__actions">
                <span class="journey-plan__hint">
                  {{ p.amendment ? 'Le plan validé a été modifié : rien ne change chez vous avant votre validation.' : 'Rien ne sera modifié avant votre validation.' }}
                </span>
                <button mat-flat-button type="button" class="journey-plan__validate" [disabled]="busy()" (click)="gesture.emit('validate-plan')">
                  {{ p.amendment ? 'Valider l\'amendement' : 'Valider le plan' }}
                </button>
              </div>
            }
          </div>
        }
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

    .journey-waiting {
      display: inline-block;
      margin-top: var(--cg-space-1);
      color: var(--cg-orange-2);
      font-size: 12px;
    }

    .journey-plan {
      margin-top: var(--cg-space-1);
    }

    .journey-plan__toggle {
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }

    .journey-plan__await,
    .journey-plan__changed {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-orange-2);
      color: var(--cg-navy);
      font-size: 12px;
      font-weight: 600;
    }

    .journey-plan__ok {
      color: var(--cg-success);
      font-size: 12px;
    }

    .journey-plan__steps {
      margin: var(--cg-space-1) 0 0;
      padding-left: var(--cg-space-5);
    }

    .journey-plan__step {
      margin-bottom: var(--cg-space-1);
    }

    .journey-plan__step--changed .journey-plan__title {
      font-weight: 600;
    }

    .journey-plan__line {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--cg-space-2);
    }

    .journey-plan__status {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-plan__status[data-status='VERIFIE'],
    .journey-plan__status[data-status='FAIT'] {
      color: var(--cg-success);
    }

    .journey-plan__status[data-status='ECHEC'] {
      color: var(--cg-error);
    }

    .journey-plan__risk {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-plan__risk[data-risk='EXTERNE'] {
      color: var(--cg-error);
    }

    .journey-plan__detail {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-plan__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .journey-plan__hint {
      color: var(--cg-text-secondary);
      font-size: 12px;
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

  /** Le plan du sujet guidé, s'il y en a un. */
  readonly plan = computed(() => (this.guided() ? this.journey()?.plan ?? null : null));

  readonly waitingInputs = computed(() => this.plan()?.waitingInputs ?? 0);

  /** Le plan est déplié tant qu'il attend une validation ; replié sinon (l'utilisateur peut l'ouvrir). */
  private readonly planToggled = signal<boolean | null>(null);
  readonly planOpen = computed(() => this.planToggled() ?? !!this.plan()?.awaitingValidation);

  togglePlan(): void {
    this.planToggled.set(!this.planOpen());
  }

  statusLabel(status: string): string {
    return stepStatusLabel(status);
  }

  waitsLabel(status: string | null): string {
    return waitsOnLabel(status);
  }

  readonly currentIndex = computed(() => {
    const phase = this.journey()?.phase;
    return phase ? JOURNEY_PHASES.findIndex(p => p.phase === phase) : -1;
  });
}
