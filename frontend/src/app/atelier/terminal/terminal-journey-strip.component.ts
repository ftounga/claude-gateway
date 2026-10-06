import { Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
  JOURNEY_PHASES, SubjectJourney, confidenceLabel, stepStatusLabel, waitsOnLabel,
} from '../../core/models/journey.models';

/** Un geste de l'utilisateur sur le parcours, rendu au terminal qui appelle la gateway. */
export type JourneyGesture = 'accept-guided' | 'decline-guided' | 'validate-plan'
  | 'confirm-diagnosis' | 'dismiss-diagnosis' | 'close' | 'dismiss-close';

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
    @if (closedLine(); as closed) {
      <section class="journey-closed" aria-label="Chantier clos">
        <div class="journey-closed__line">
          <mat-icon class="journey-closed__icon" aria-hidden="true">task_alt</mat-icon>
          <span class="journey-closed__text">Chantier clos{{ closed.date ? ' le ' + closed.date : '' }}</span>
          @if (closed.plan) {
            <span aria-hidden="true">·</span>
            <button type="button" class="journey-closed__toggle" [attr.aria-expanded]="closedPlanOpen()" (click)="closedPlanOpen.set(!closedPlanOpen())">
              {{ closedPlanOpen() ? 'masquer le plan' : 'voir le plan' }}
            </button>
          }
          <button type="button" class="journey-closed__dismiss" aria-label="Masquer cette ligne" (click)="dismissClosed()">
            <mat-icon aria-hidden="true">close</mat-icon>
          </button>
        </div>
        @if (closedPlanOpen() && closed.plan; as p) {
          <ol class="journey-plan__steps">
            @for (step of p.steps; track $index) {
              <li class="journey-plan__step">
                <div class="journey-plan__line">
                  <span class="journey-plan__status" [attr.data-status]="step.status">{{ statusLabel(step.status) }}</span>
                  <span class="journey-plan__title">{{ step.title }}</span>
                  <span class="journey-plan__risk" [attr.data-risk]="step.risk">{{ step.riskLabel }}</span>
                </div>
                @if (step.evidence) {
                  <div class="journey-plan__detail">Preuve : {{ step.evidence }}</div>
                }
              </li>
            }
          </ol>
        }
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
        @if (pendingDiagnosis(); as d) {
          <div class="journey-decision" role="region" aria-label="Diagnostic prêt à planifier">
            <div class="journey-decision__body">
              <strong>Prêt à planifier</strong> — confiance {{ confidence(d.confidence) }}
              <span class="journey-decision__text">{{ d.text }}</span>
              @if (d.evidence) {
                <span class="journey-decision__hint">Preuves : {{ d.evidence }}</span>
              }
            </div>
            <div class="journey-card__actions">
              <button mat-flat-button type="button" class="journey-decision__confirm" [disabled]="busy()" (click)="gesture.emit('confirm-diagnosis')">Planifier</button>
              <button mat-button type="button" class="journey-decision__dismiss" [disabled]="busy()" (click)="gesture.emit('dismiss-diagnosis')">Continuer l'investigation</button>
            </div>
          </div>
        }
        @if (closeProposed()) {
          <div class="journey-decision" role="region" aria-label="Clôture proposée">
            <div class="journey-decision__body">
              <strong>Toutes les vérifications sont vertes.</strong>
              <span class="journey-decision__hint">Le sujet peut être clos ; il pourra être rouvert en mode guidé.</span>
            </div>
            <div class="journey-card__actions">
              <button mat-flat-button type="button" class="journey-decision__close" [disabled]="busy()" (click)="gesture.emit('close')">Clore le sujet</button>
              <button mat-button type="button" class="journey-decision__later" [disabled]="busy()" (click)="gesture.emit('dismiss-close')">Pas encore</button>
            </div>
          </div>
        }
        @if (gateClosed()) {
          <span class="journey-gate">
            <mat-icon class="journey-gate__icon" aria-hidden="true">lock</mat-icon>
            {{ gateText() }}
          </span>
        }
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
    .journey-card,
    .journey-closed {
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

    .journey-closed {
      color: var(--cg-text-secondary);
    }

    .journey-closed__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1) var(--cg-space-2);
    }

    .journey-closed__icon {
      color: var(--cg-success);
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .journey-closed__toggle,
    .journey-closed__dismiss {
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }

    .journey-closed__toggle {
      text-decoration: underline;
    }

    .journey-closed__dismiss {
      display: inline-flex;
      margin-left: auto;
    }

    .journey-closed__dismiss mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
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

    .journey-decision {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2) var(--cg-space-3);
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-orange-2);
      border-radius: 8px;
    }

    .journey-decision__body {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-width: 12rem;
      gap: 2px;
    }

    .journey-decision__hint {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-gate {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin-top: var(--cg-space-1);
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .journey-gate__icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
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

  /** La bande des phases n'est rendue qu'en Guidé **actif** (SF-176-08, D8) — jamais sur un chantier clos. */
  readonly guided = computed(() => {
    const j = this.journey();
    return !!j && j.mode === 'GUIDE' && !!j.phase && j.phase !== 'CLOS';
  });

  /** Les lignes « chantier clos » masquées par l'utilisateur (clé = date de clôture). */
  private readonly dismissedClosed = signal<string | null>(readDismissed());

  /**
   * **Le chantier clos, replié en une ligne** (SF-176-08, D8) : « Chantier clos le … · voir le plan »,
   * masquable. Rien si le sujet n'a jamais été clos, ou si la ligne a été masquée.
   */
  readonly closedLine = computed(() => {
    const j = this.journey();
    if (!j || j.mode === 'GUIDE' || j.phase !== 'CLOS') {
      return null;
    }
    const key = j.phaseChangedAt ?? 'clos';
    if (this.dismissedClosed() === key) {
      return null;
    }
    return { date: closedDate(j.phaseChangedAt), plan: j.plan && j.plan.steps.length > 0 ? j.plan : null };
  });

  readonly closedPlanOpen = signal(false);

  /** Masque la ligne du chantier clos (mémorisé dans ce navigateur, best-effort). */
  dismissClosed(): void {
    const key = this.journey()?.phaseChangedAt ?? 'clos';
    this.dismissedClosed.set(key);
    this.closedPlanOpen.set(false);
    try {
      localStorage.setItem(DISMISSED_KEY, key);
    } catch {
      // stockage indisponible : masqué pour cette session seulement
    }
  }

  /** La proposition du mode guidé qui attend un geste — seulement en Libre. */
  readonly proposal = computed(() => {
    const j = this.journey();
    return j && j.mode !== 'GUIDE' ? j.guidedProposal ?? null : null;
  });

  /** Le plan du sujet guidé, s'il y en a un. */
  readonly plan = computed(() => (this.guided() ? this.journey()?.plan ?? null : null));

  readonly waitingInputs = computed(() => this.plan()?.waitingInputs ?? 0);

  /**
   * La porte est fermée (SF-176-04) : en Guidé, hors Exécution sur le plan validé, seules la lecture et
   * les notes passent. On le dit, pour qu'un refus de l'agent ne passe pas pour une panne.
   */
  readonly gateClosed = computed(() => {
    const j = this.journey();
    if (!this.guided() || !j) {
      return false;
    }
    if (j.gateClosed !== undefined) {
      return j.gateClosed; // SF-176-07 : une seule source, la gateway
    }
    const p = j.plan;
    const planCurrent = !!p && p.validatedVersion !== null && p.validatedVersion === p.version;
    return !(j.phase === 'EXECUTION' && planCurrent) && j.phase !== 'CLOS';
  });

  /** Le message exact du refus (SF-176-07, D5), suivi de ce qui reste libre. */
  readonly gateText = computed(() => {
    const message = this.journey()?.gateMessage;
    return message
      ? `${message} Lecture et notes restent libres.`
      : 'Lecture et notes libres · les modifications attendent un plan validé';
  });

  /** Le plan est déplié tant qu'il attend une validation ; replié sinon (l'utilisateur peut l'ouvrir). */
  private readonly planToggled = signal<boolean | null>(null);
  readonly planOpen = computed(() => this.planToggled() ?? !!this.plan()?.awaitingValidation);

  togglePlan(): void {
    this.planToggled.set(!this.planOpen());
  }

  /** Le diagnostic qui attend « Planifier » (SF-176-05). */
  readonly pendingDiagnosis = computed(() => {
    const j = this.journey();
    return this.guided() && j?.phase === 'INVESTIGATION' && j.diagnosis?.pending ? j.diagnosis : null;
  });

  /** La clôture proposée (SF-176-05). */
  readonly closeProposed = computed(() => this.guided() && !!this.journey()?.closeProposed);

  confidence(value: string): string {
    return confidenceLabel(value);
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

const DISMISSED_KEY = 'cg.journey.closedDismissed';

function readDismissed(): string | null {
  try {
    return localStorage.getItem(DISMISSED_KEY);
  } catch {
    return null;
  }
}

/** « 06/10/2026 », ou `null` si la date est illisible. */
function closedDate(iso: string | null): string | null {
  if (!iso) {
    return null;
  }
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d.toLocaleDateString('fr-FR');
}
