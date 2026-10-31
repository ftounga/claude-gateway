import { Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  AutonomyBoard,
  AutonomyBoardEntry,
  AutonomyConflict,
  AutonomyMode,
  autonomyCurrentStep,
  autonomyOutcomeLabel,
  autonomyRunningLabel,
  groupRemaining,
} from '../../core/models/autonomy.models';
import { AutonomyService } from '../../core/services/autonomy.service';

import {
  JOURNEY_PHASES,
  JourneyPhase,
  JourneyPlanStep,
  JourneyPlanVersion,
  OpenChantier,
  SubjectJourney,
  chantierGestures,
  currentStepIndex,
  idleDaysOf,
  stepState,
  stepStateIcon,
  stepStateLabel,
} from '../../core/models/journey.models';
import { JourneyService } from '../../core/services/journey.service';
import { StepActorComponent } from '../../shared/step-actor/step-actor.component';
import { UnblockerPillComponent } from '../../shared/unblocker-pill/unblocker-pill.component';

/** Circonférence de l'anneau d'avancement (rayon 18). */
const RING = 2 * Math.PI * 18;

/**
 * **Le tableau des chantiers** (F-208 / SF-208-03) : un overlay — pas une page — ouvert depuis la barre
 * d'état du terminal. Un onglet par chantier ouvert (le focus d'abord) : frise des phases, anneau
 * d'avancement, **toutes** les étapes avec leur état (vérifiée, faite, en cours, bloquée, à faire, échec),
 * leurs sous-étapes et leurs preuves, et l'historique des versions du plan.
 *
 * <p>Il ne lit rien d'autre que le parcours du terminal (`journey`), relu par le terminal à chaque fin de
 * tour **et pendant le tour** (événement SSE `journey`) : l'overlay suit sans geste. Seul l'historique est
 * lu à la demande.</p>
 */
@Component({
  selector: 'app-terminal-chantiers-board',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, StepActorComponent, UnblockerPillComponent],
  template: `
    <div class="board-scrim" (click)="closed.emit()" aria-hidden="true"></div>
    <aside class="board cg-light-island" role="dialog" aria-label="Tableau des chantiers">
      <header class="board__bar">
        <h2 class="board__title">Chantiers</h2>
        <span class="board__spacer"></span>
        <button mat-icon-button type="button" class="board__close" matTooltip="Fermer (Échap)"
          aria-label="Fermer le tableau des chantiers" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>

      @if (tabs().length === 0) {
        <p class="board__empty">Aucun chantier ouvert sur ce sujet.</p>
      } @else {
        <div class="board__tabs" role="tablist" aria-label="Chantiers ouverts">
          @for (c of tabs(); track c.number) {
            <button type="button" role="tab" class="board__tab"
              [class.board__tab--active]="c.number === selected()?.number"
              [attr.aria-selected]="c.number === selected()?.number"
              (click)="select(c.number)">
              <span class="board__tab-number">Ch.{{ c.number }}</span>
              <span class="board__tab-title">{{ c.title || ('Chantier ' + c.number) }}</span>
              @if (c.focus) {
                <span class="board__tab-focus">focus</span>
              }
              <!-- F-220 / SF-220-04 : qui débloque ce chantier — même pastille que le tableau du client. -->
              <app-unblocker-pill class="board__tab-who" size="sm" [mark]="c.unblocker"></app-unblocker-pill>
              @if (gestures(c) > 0) {
                <span class="board__await">à vous</span>
              }
              @if (running(c.number)) {
                <span class="board__tab-auto">autonome</span>
              }
              @if (c.stale) {
                <span class="board__tab-stale">en sommeil</span>
              }
            </button>
          }
        </div>

        @if (selected(); as c) {
          <section class="board__chantier" role="tabpanel" [attr.aria-label]="'Chantier ' + c.number">
            <div class="board__summary">
              <svg class="board__ring" viewBox="0 0 44 44" role="img" [attr.aria-label]="c.done + ' étapes faites sur ' + c.total">
                <circle class="board__ring-track" cx="22" cy="22" r="18"></circle>
                <circle class="board__ring-value" cx="22" cy="22" r="18" [attr.stroke-dasharray]="ringDash(c)"></circle>
              </svg>
              <div class="board__summary-text">
                <h3 class="board__chantier-title">Chantier {{ c.number }}{{ c.title ? ' — ' + c.title : '' }}</h3>
                <p class="board__count"><strong>{{ c.done }}/{{ c.total }}</strong> étapes faites · {{ planLabel(c) }}</p>
                @if (c.unblocker; as who) {
                  <p class="board__who">
                    <app-unblocker-pill [mark]="who"></app-unblocker-pill>
                    <span class="board__who-detail">{{ who.detail }}</span>
                  </p>
                }
              </div>
              @if (c.diagnosisPending && canSend()) {
                <!-- F-176 / SF-176-12 : [Planifier] sur la carte, sans changer le focus. -->
                <button mat-flat-button type="button" class="board__plan-btn" [disabled]="busy()" (click)="planify(c)">
                  <mat-icon>event_note</mat-icon>
                  Planifier
                </button>
              }
              @if (c.stale) {
                <!-- F-222 / SF-222-03 : un chantier en Exécution sans geste depuis des jours — visible, jamais clos seul. -->
                <p class="board__stale" role="status">
                  <mat-icon aria-hidden="true">bedtime</mat-icon>
                  {{ staleLabel(c) }}
                  @if (canSend()) {
                    <button mat-stroked-button type="button" class="board__stale-close" [disabled]="busy()"
                      (click)="closeStale(c)">Clore</button>
                  }
                </p>
              }
              @if (!c.focus) {
                <button mat-stroked-button type="button" class="board__focus-btn" (click)="focus.emit(c.number)">
                  <mat-icon>center_focus_strong</mat-icon>
                  Mettre au focus
                </button>
              }
            </div>

            @if (c.attentes?.length) {
              <!-- F-227 / SF-227-02 : les attentes nées dans ce chantier (ou rattachées), même sans étape qui les attend. -->
              <section class="board__attentes" aria-label="Attentes rangées sous ce chantier">
                <h4 class="board__attentes-title">Attentes de ce chantier · {{ c.attentes!.length }}</h4>
                <ul class="board__attentes-list">
                  @for (a of c.attentes!; track a.id) {
                    <li class="board__attente">
                      <span class="board__attente-status">{{ a.status === 'DEMANDE' ? 'demandée' : 'à faire' }}</span>
                      <span class="board__attente-text">{{ a.description }}</span>
                      @if (a.person) {
                        <span class="board__attente-person">· {{ a.person }}</span>
                      }
                    </li>
                  }
                </ul>
              </section>
            }

            @if (autonomyOf(c.number); as auto) {
              <section class="auto" aria-label="Exécution en autonome" [attr.data-state]="autoState(auto)">
                @if (auto.run?.status === 'RUNNING') {
                  <p class="auto__status auto__status--running" role="status">
                    <mat-icon class="auto__spin" aria-hidden="true">autorenew</mat-icon>
                    {{ runningLabel(auto, c) }}
                  </p>
                  <p class="auto__hint">Vous pouvez quitter l’écran : le travail continue côté serveur.</p>
                  @if (canSend()) {
                    <button mat-stroked-button type="button" class="auto__stop" [disabled]="busy()" (click)="takeBack(c)">
                      <mat-icon>pan_tool</mat-icon>
                      Reprendre la main
                    </button>
                  }
                } @else if (auto.preflight.ready) {
                  <p class="auto__status auto__status--ready">
                    <mat-icon aria-hidden="true">rocket_launch</mat-icon>
                    Prêt à partir
                  </p>
                  @if (canSend()) {
                    <div class="auto__launch">
                      <button mat-flat-button type="button" class="auto__go" [disabled]="busy()" (click)="launch(c)">
                        <mat-icon>play_circle</mat-icon>
                        Lancer en autonome
                      </button>
                      <button mat-stroked-button type="button" class="auto__advance" [disabled]="busy()"
                              (click)="launch(c, 'UNTIL_BLOCKED')">
                        <mat-icon>fast_forward</mat-icon>
                        Avancer jusqu’au blocage
                      </button>
                      <span class="auto__meta">
                        @if (auto.estimate; as est) {
                          ≈ {{ est.usd.toFixed(2) }} $ · {{ est.basis }} ·
                        }
                        {{ autonomy()?.running ?? 0 }}/{{ autonomy()?.maxParallel ?? 4 }} chantiers autonomes en cours
                      </span>
                    </div>
                  }
                } @else if (auto.preflight.advance?.possible) {
                  <p class="auto__status auto__status--advance">
                    <mat-icon aria-hidden="true">fast_forward</mat-icon>
                    Peut avancer jusqu’au blocage
                  </p>
                  @if (canSend()) {
                    <div class="auto__launch">
                      <button mat-flat-button type="button" class="auto__go auto__advance" [disabled]="busy()"
                              (click)="launch(c, 'UNTIL_BLOCKED')">
                        <mat-icon>fast_forward</mat-icon>
                        Avancer jusqu’au blocage
                      </button>
                      <span class="auto__meta">
                        {{ auto.preflight.advance?.label }} ·
                        {{ autonomy()?.running ?? 0 }}/{{ autonomy()?.maxParallel ?? 4 }} chantiers autonomes en cours
                      </span>
                    </div>
                  }
                } @else {
                  <p class="auto__status auto__status--not-ready">
                    <mat-icon aria-hidden="true">block</mat-icon>
                    Pas prêt à partir en autonome
                  </p>
                  @if (!auto.preflight.advance?.remaining?.length) {
                    <ul class="auto__reasons" aria-label="Ce qui empêche le départ">
                      @for (r of auto.preflight.reasons; track $index) {
                        <li>{{ r.label }}</li>
                      }
                    </ul>
                  }
                }
                @if (auto.run?.status !== 'RUNNING' && !auto.preflight.ready && auto.preflight.advance?.remaining?.length) {
                  @let groups = remainingGroups(auto);
                  @let rest = auto.preflight.advance?.possible ? 'Ce qui restera' : 'Ce qui reste';
                  <div class="auto__rest" role="group" [attr.aria-label]="rest">
                    <p class="auto__rest-title">{{ rest }}</p>
                    @if (groups.decisions.length) {
                      <p class="auto__rest-group" data-who="MOI">
                        <mat-icon aria-hidden="true">how_to_reg</mat-icon>
                        Ça attend votre décision
                      </p>
                      <ul class="auto__reasons">
                        @for (m of groups.decisions; track $index) {
                          <li>{{ m.detail }}</li>
                        }
                      </ul>
                    }
                    @if (groups.waits.length) {
                      <p class="auto__rest-group" data-who="AUTRUI">
                        <mat-icon aria-hidden="true">hourglass_top</mat-icon>
                        On attend quelqu’un
                      </p>
                      <ul class="auto__reasons">
                        @for (m of groups.waits; track $index) {
                          <li>{{ m.detail }}@if (m.waitingOn) { <span class="auto__who"> — {{ m.waitingOn }}</span> }</li>
                        }
                      </ul>
                    }
                  </div>
                }
                @if (auto.run && auto.run.status !== 'RUNNING') {
                  <p class="auto__last">
                    Dernière course : {{ outcome(auto.run.status) }}{{ auto.run.stopReason ? ' — ' + auto.run.stopReason : '' }}
                  </p>
                }
              </section>
            }

            <ol class="board__phases" aria-label="Phases">
              @for (p of phases; track p.phase) {
                <li class="board__phase" [attr.data-state]="phaseState(c, p.phase)">
                  @if (phaseState(c, p.phase) === 'past') {
                    <mat-icon class="board__phase-icon" aria-hidden="true">check</mat-icon>
                  }
                  {{ p.label }}
                </li>
              }
            </ol>

            @if (c.plan; as plan) {
              <ol class="board__steps" aria-label="Étapes du plan">
                @for (step of plan.steps; track $index; let i = $index) {
                  <li class="board__step" [attr.data-state]="state(step)">
                    <button type="button" class="board__step-head" [attr.aria-expanded]="isOpen(c, i)" (click)="toggle(c, i)">
                      <mat-icon class="board__step-icon" aria-hidden="true">{{ icon(step) }}</mat-icon>
                      <span class="board__step-rank">{{ i + 1 }}</span>
                      <span class="board__step-title">{{ step.title }}</span>
                      <span class="board__step-state">{{ stateLabel(step) }}</span>
                    </button>
                    <p class="board__step-meta">
                      <app-unblocker-pill class="board__step-who" size="sm" [mark]="step.unblocker"></app-unblocker-pill>
                      <app-step-actor class="board__step-actor" [actor]="step.actor"></app-step-actor>
                      <span class="board__risk" [attr.data-risk]="step.risk">{{ step.riskLabel }}</span>
                      @if (blockedText(step); as blocked) {
                        <span class="board__blocked">{{ blocked }}</span>
                      }
                      @if (step.dependsOn?.length && state(step) !== 'blocked') {
                        <span class="board__after">après {{ step.dependsOn!.join(', ') }}</span>
                      }
                      @if (step.subSteps?.length) {
                        <span class="board__subcount">sous-étapes {{ subDone(step) }}/{{ step.subSteps!.length }}</span>
                      }
                      @if (step.changed) {
                        <span class="board__changed">modifiée</span>
                      }
                    </p>
                    @if (hasActions(c, step)) {
                      <div class="board__actions">
                        @if (canSend() && doable(step)) {
                          <button mat-stroked-button type="button" class="board__do"
                            [disabled]="!!doBlockedReason(c, step)" (click)="askDo(c, i, step)">
                            <mat-icon>play_arrow</mat-icon>
                            Faire cette étape
                          </button>
                          @if (doBlockedReason(c, step); as reason) {
                            <span class="board__reason">{{ reason }}</span>
                          }
                        }
                        @if (canSend() && waitOpen(step)) {
                          <button mat-button type="button" class="board__remind" (click)="remind(c, i, step)">
                            <mat-icon>notifications_active</mat-icon>
                            Relancer l’attente
                          </button>
                        }
                        @if (hasProof(step)) {
                          <button mat-button type="button" class="board__see-proof" (click)="seeProof(c, i)">
                            <mat-icon>fact_check</mat-icon>
                            Voir la preuve
                          </button>
                        }
                      </div>
                    }
                    @if (isOpen(c, i)) {
                      <div class="board__detail">
                        @if (step.subSteps?.length) {
                          <ul class="board__subs" aria-label="Sous-étapes">
                            @for (sub of step.subSteps!; track $index) {
                              <li class="board__sub" [class.board__sub--done]="sub.done">
                                <mat-icon class="board__sub-icon" aria-hidden="true">{{ sub.done ? 'check_box' : 'check_box_outline_blank' }}</mat-icon>
                                <span>{{ sub.title }}</span>
                                @if (sub.evidence) {
                                  <span class="board__evidence" [class.board__proof--shown]="proofShown() === key(c, i)">— {{ sub.evidence }}</span>
                                }
                              </li>
                            }
                          </ul>
                        }
                        @if (step.verify) {
                          <p class="board__line"><span class="board__label">Vérification</span> {{ step.verify }}</p>
                        }
                        @if (step.rollback) {
                          <p class="board__line"><span class="board__label">Retour arrière</span> {{ step.rollback }}</p>
                        }
                        @if (step.evidence) {
                          <p class="board__line board__proof" [class.board__proof--shown]="proofShown() === key(c, i)">
                            <span class="board__label">Preuve</span> {{ step.evidence }}
                          </p>
                        }
                        @if (!step.subSteps?.length && !step.verify && !step.rollback && !step.evidence) {
                          <p class="board__line board__muted">Pas de détail pour cette étape.</p>
                        }
                      </div>
                    }
                  </li>
                }
              </ol>
            } @else {
              <p class="board__empty">Pas encore de plan — phase {{ c.phaseLabel || 'inconnue' }}.</p>
            }

            <section class="board__history">
              <button type="button" class="board__history-toggle" [attr.aria-expanded]="historyOpen()" (click)="toggleHistory()">
                <mat-icon aria-hidden="true">{{ historyOpen() ? 'expand_less' : 'expand_more' }}</mat-icon>
                Historique du plan
              </button>
              @if (historyOpen()) {
                @switch (historyState()) {
                  @case ('loading') {
                    <p class="board__muted">Chargement…</p>
                  }
                  @case ('error') {
                    <p class="board__muted">{{ historyError }}</p>
                  }
                  @default {
                    @if (versions().length === 0) {
                      <p class="board__muted">{{ historyEmpty }}</p>
                    } @else {
                      <ol class="board__versions">
                        @for (v of versionsNewestFirst(); track v.version) {
                          <li class="board__version">
                            <strong>v{{ v.version }}</strong> · {{ v.stepCount }} étape{{ v.stepCount > 1 ? 's' : '' }}
                            @for (t of v.added; track $index) {
                              <span class="board__diff board__diff--added">+ {{ t }}</span>
                            }
                            @for (t of v.removed; track $index) {
                              <span class="board__diff board__diff--removed">− {{ t }}</span>
                            }
                            @for (t of v.modified; track $index) {
                              <span class="board__diff board__diff--modified">~ {{ t }}</span>
                            }
                          </li>
                        }
                      </ol>
                    }
                  }
                }
              }
            </section>
          </section>
        }
      }
    </aside>
  `,
  styles: `
    :host {
      position: fixed;
      inset: 0;
      z-index: 900;
    }

    .board-scrim {
      position: absolute;
      inset: 0;
      background: color-mix(in srgb, var(--cg-navy) 32%, transparent);
    }

    .board {
      position: absolute;
      top: var(--cg-shell-bar-h, 64px);
      right: 0;
      bottom: 0;
      width: min(760px, 92vw);
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background: var(--cg-surface);
      border-left: 1px solid var(--cg-divider);
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
      color: var(--cg-text-primary);
      /* ÎLOT CLAIR posé sur le terminal sombre (même remède que le panneau des attentes). */
      --mdc-icon-button-icon-color: var(--cg-text-secondary);
      font-size: 14px;
    }

    @media (max-width: 899px) {
      .board {
        top: 0;
        width: 100vw;
        border-left: 0;
      }
    }

    .board__bar {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2) var(--cg-space-1) var(--cg-space-4);
      border-bottom: 1px solid var(--cg-divider);
    }

    .board__title {
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
    }

    .board__spacer {
      flex: 1;
    }

    .board__tabs {
      display: flex;
      gap: var(--cg-space-1);
      padding: var(--cg-space-2) var(--cg-space-4) 0;
      overflow-x: auto;
      border-bottom: 1px solid var(--cg-divider);
    }

    .board__tab {
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      max-width: 280px;
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 0;
      border-bottom: 2px solid transparent;
      background: transparent;
      color: var(--cg-text-secondary);
      font: inherit;
      white-space: nowrap;
      cursor: pointer;
    }

    .board__tab--active {
      border-bottom-color: var(--cg-accent);
      color: var(--cg-text-primary);
      font-weight: 600;
    }

    .board__tab-number {
      font-family: var(--cg-font-mono);
      font-size: 12px;
    }

    .board__tab-title {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .board__tab-focus {
      padding: 0 var(--cg-space-1);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      font-size: 11px;
      font-weight: 400;
    }

    .board__await {
      padding: 0 6px;
      border-radius: 8px;
      background: var(--cg-orange-2);
      color: var(--cg-navy);
      font-size: 11px;
      font-weight: 600;
    }

    .board__tab-stale {
      padding: 0 6px;
      border-radius: 8px;
      background: #FFF8E1;
      color: var(--cg-text-primary);
      font-size: 11px;
      font-weight: 600;
    }

    .board__tab-auto {
      padding: 0 6px;
      border-radius: 8px;
      background: var(--cg-success);
      color: var(--cg-navy);
      font-size: 11px;
      font-weight: 600;
    }

    .auto {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-3);
      padding: var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
    }

    .auto[data-state='ready'] {
      border-color: var(--cg-success);
    }

    .auto[data-state='running'] {
      border-color: var(--cg-accent);
    }

    .auto[data-state='advance'] {
      border-color: var(--cg-accent);
      border-style: dashed;
    }

    .auto__status {
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin: 0;
      font-weight: 600;
    }

    /* Texte en encre (AA) ; la couleur d'état porte l'icône et le liseré, jamais seule. */
    .auto__status--ready {
      color: var(--cg-text-primary);
    }

    .auto__status--ready mat-icon {
      color: var(--cg-success);
    }

    .auto__status--running {
      color: var(--cg-gold-ink);
    }

    .auto__status--not-ready {
      color: var(--cg-text-secondary);
    }

    .auto__spin {
      animation: auto-spin 1.6s linear infinite;
    }

    @keyframes auto-spin {
      to {
        transform: rotate(360deg);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .auto__spin {
        animation: none;
      }
    }

    .auto__hint,
    .auto__meta,
    .auto__last {
      margin: 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .auto__launch {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .auto__go {
      --mdc-filled-button-container-color: var(--cg-orange-2);
      --mdc-filled-button-label-text-color: var(--cg-navy);
    }

    .auto__stop {
      align-self: flex-start;
    }

    .auto__status--advance {
      color: var(--cg-text-primary);
    }

    .auto__rest {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
    }

    .auto__rest-title {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .auto__rest-group {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin: 0;
      font-size: 12px;
      font-weight: 600;
      color: var(--cg-text-secondary);
    }

    .auto__rest-group mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }

    .auto__who {
      color: var(--cg-text-secondary);
    }

    .auto__reasons {
      margin: 0;
      padding-left: var(--cg-space-4);
      font-size: 13px;
      color: var(--cg-text-primary);
    }

    @media (max-width: 599px) {
      .auto__launch {
        flex-direction: column;
        align-items: stretch;
      }
    }

    .board__chantier {
      flex: 1;
      overflow-y: auto;
      padding: var(--cg-space-3) var(--cg-space-4) var(--cg-space-4);
    }

    .board__summary {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-3);
    }

    .board__ring {
      flex: 0 0 56px;
      width: 56px;
      height: 56px;
      transform: rotate(-90deg);
    }

    .board__ring-track,
    .board__ring-value {
      fill: none;
      stroke-width: 5;
    }

    .board__ring-track {
      stroke: var(--cg-divider);
    }

    .board__ring-value {
      stroke: var(--cg-success);
      stroke-linecap: round;
    }

    .board__chantier-title {
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 16px;
      font-weight: 600;
    }

    .board__count {
      margin: var(--cg-space-1) 0 0;
      color: var(--cg-text-secondary);
    }

    .board__attentes {
      margin: var(--cg-space-2) var(--cg-space-4) 0;
    }

    .board__attentes-title {
      margin: 0 0 var(--cg-space-1);
      font: 600 12px 'Inter', sans-serif;
      color: var(--cg-text-secondary);
    }

    .board__attentes-list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .board__attente {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1) var(--cg-space-2);
      padding: var(--cg-space-1) 0;
      font-size: 13px;
    }

    .board__attente-status {
      color: var(--cg-gold-ink);
      font-weight: 600;
    }

    .board__attente-person {
      color: var(--cg-text-secondary);
    }

    /* F-222 / SF-222-03 : chantier en sommeil — ambre de l'attente (§12), filet seul, jamais un fond. */
    .board__stale {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      flex-basis: 100%;
      margin: 0;
      padding-left: var(--cg-space-2);
      border-left: 4px solid #F9A825;
      font-size: 13px;
      color: var(--cg-text-primary);
    }

    .board__stale mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    /* F-220 / SF-220-04 : qui débloque — la pastille écrit son libellé, la phrase dit le détail. */
    .board__who {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin: var(--cg-space-2) 0 0;
      font-size: 13px;
      color: var(--cg-text-primary);
    }

    .board__phases {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1);
      margin: var(--cg-space-3) 0;
      padding: 0;
      list-style: none;
    }

    .board__phase {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      padding: 2px var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 12px;
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .board__phase[data-state='past'] {
      color: var(--cg-success);
    }

    .board__phase[data-state='current'] {
      border-color: var(--cg-accent);
      background: var(--cg-accent);
      color: var(--cg-surface);
      font-weight: 600;
    }

    .board__phase-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
    }

    .board__steps {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .board__step {
      padding: var(--cg-space-2) 0;
      border-top: 1px solid var(--cg-divider);
    }

    .board__step-head {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      width: 100%;
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .board__step-icon {
      flex: 0 0 20px;
      font-size: 20px;
      width: 20px;
      height: 20px;
      color: var(--cg-text-secondary);
    }

    .board__step[data-state='verified'] .board__step-icon,
    .board__step[data-state='done'] .board__step-icon {
      color: var(--cg-success);
    }

    .board__step[data-state='failed'] .board__step-icon {
      color: var(--cg-error);
    }

    .board__step[data-state='progress'] .board__step-icon {
      color: var(--cg-accent);
    }

    .board__step-rank {
      flex: 0 0 auto;
      font-family: var(--cg-font-mono);
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .board__step-title {
      flex: 1;
      min-width: 0;
    }

    .board__step[data-state='done'] .board__step-title,
    .board__step[data-state='verified'] .board__step-title {
      color: var(--cg-text-secondary);
    }

    .board__step-state {
      flex: 0 0 auto;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .board__step[data-state='progress'] .board__step-state {
      color: var(--cg-accent);
      font-weight: 600;
    }

    .board__step[data-state='failed'] .board__step-state {
      color: var(--cg-error);
      font-weight: 600;
    }

    .board__step-meta {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1) var(--cg-space-2);
      margin: var(--cg-space-1) 0 0 calc(20px + var(--cg-space-2));
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .board__risk[data-risk='EXTERNE'] {
      color: var(--cg-error);
    }

    .board__blocked {
      color: var(--cg-text-primary);
    }

    .board__changed {
      color: var(--cg-accent);
    }

    .board__detail {
      margin: var(--cg-space-2) 0 0 calc(20px + var(--cg-space-2));
      padding: var(--cg-space-2) var(--cg-space-3);
      border-left: 2px solid var(--cg-divider);
    }

    .board__subs {
      margin: 0 0 var(--cg-space-2);
      padding: 0;
      list-style: none;
    }

    .board__sub {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      padding: 2px 0;
    }

    .board__sub-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: var(--cg-text-secondary);
    }

    .board__sub--done .board__sub-icon {
      color: var(--cg-success);
    }

    .board__evidence,
    .board__muted {
      color: var(--cg-text-secondary);
    }

    .board__line {
      margin: var(--cg-space-1) 0;
    }

    .board__label {
      margin-right: var(--cg-space-1);
      font-size: 12px;
      font-weight: 600;
      color: var(--cg-text-secondary);
    }

    .board__proof {
      font-family: var(--cg-font-mono);
      font-size: 12px;
      word-break: break-word;
    }

    .board__empty {
      padding: var(--cg-space-4);
      color: var(--cg-text-secondary);
    }

    .board__history {
      margin-top: var(--cg-space-4);
      padding-top: var(--cg-space-2);
      border-top: 1px solid var(--cg-divider);
    }

    .board__history-toggle {
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--cg-text-primary);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }

    .board__versions {
      margin: var(--cg-space-2) 0 0;
      padding: 0;
      list-style: none;
    }

    .board__version {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1) var(--cg-space-2);
      padding: var(--cg-space-1) 0;
      font-size: 13px;
    }

    .board__focus-btn {
      margin-left: auto;
    }

    .board__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1) var(--cg-space-2);
      margin: var(--cg-space-1) 0 0 calc(20px + var(--cg-space-2));
    }

    .board__reason {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .board__proof--shown {
      outline: 2px solid var(--cg-accent);
      outline-offset: 2px;
      border-radius: 4px;
    }

    .board__diff--added {
      color: var(--cg-success);
    }

    .board__plan-btn {
      margin-left: auto;
      flex-shrink: 0;
    }

    .board__plan-btn + .board__focus-btn {
      margin-left: 0;
    }

    .board__diff--removed {
      color: var(--cg-error);
    }

    .board__diff--modified {
      color: var(--cg-accent);
    }
  `,
})
export class TerminalChantiersBoardComponent {
  /** Le parcours du terminal, relu en direct par le terminal. */
  readonly journey = input<SubjectJourney | null>(null);
  /** Le terminal (pour l'historique). */
  readonly workspaceId = input<string | null>(null);

  /** Le terminal peut envoyer une demande (ni lecture seule, ni plafond atteint). */
  readonly canSend = input(true);

  readonly closed = output<void>();
  /** Une demande visible à envoyer dans le fil (F-208 / SF-208-04). */
  readonly request = output<string>();
  /** [Mettre au focus] le chantier N (F-208 / SF-208-04). */
  readonly focus = output<number>();
  /** Le parcours a changé côté serveur ([Planifier], F-176 / SF-176-12) : le terminal le relit. */
  readonly changed = output<void>();

  /**
   * Le chantier que montre le volet qui ouvre ce tableau (F-221 / SF-221-04), ou `null`. Lancer un AUTRE
   * chantier émet {@link parallel} au lieu de partir : l'écran demande « en parallèle ? ».
   */
  readonly shownChantier = input<number | null>(null);

  /** [Lancer] / [Avancer] sur un autre chantier que celui du volet (F-221 / SF-221-04). */
  readonly parallel = output<{ number: number; mode: AutonomyMode }>();

  private readonly journeys = inject(JourneyService);
  private readonly autonomyApi = inject(AutonomyService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly host = inject(ElementRef<HTMLElement>);

  /** L'autonomie des chantiers du sujet (F-219 / SF-219-03) ; `null` tant qu'elle n'est pas lue. */
  readonly autonomy = signal<AutonomyBoard | null>(null);
  /** Un lancement ou une reprise en main est en vol. */
  readonly busy = signal(false);
  /** Relecture pendant une course (5 s). */
  private poll: ReturnType<typeof setInterval> | null = null;

  readonly phases = JOURNEY_PHASES;
  readonly historyError = 'L’historique n’a pas pu être lu.';
  /** L'étape dont la preuve vient d'être demandée : `numéro:rang`. */
  readonly proofShown = signal<string | null>(null);
  readonly historyEmpty = 'L’historique est conservé à partir de la prochaine version du plan.';

  /** L'onglet choisi ; `null` = le chantier au focus. */
  private readonly chosen = signal<number | null>(null);
  /** Les étapes dépliées ou repliées à la main : `numéro:rang` → ouvert. */
  private readonly manual = signal<Record<string, boolean>>({});

  readonly historyOpen = signal(false);
  readonly historyState = signal<'loading' | 'error' | 'ok'>('ok');
  readonly versions = signal<JourneyPlanVersion[]>([]);
  readonly versionsNewestFirst = computed(() => [...this.versions()].reverse());

  /** Les chantiers ouverts, le focus d'abord, puis par numéro. */
  readonly tabs = computed<OpenChantier[]>(() => {
    const open = [...(this.journey()?.chantiers ?? [])];
    return open.sort((a, b) => (a.focus === b.focus ? a.number - b.number : a.focus ? -1 : 1));
  });

  readonly selected = computed<OpenChantier | null>(() => {
    const tabs = this.tabs();
    const chosen = this.chosen();
    return tabs.find((c) => c.number === chosen) ?? tabs[0] ?? null;
  });

  constructor() {
    // F-219 / SF-219-03 : l'autonomie est relue à l'ouverture et à chaque changement du parcours.
    effect(() => {
      this.journey();
      const workspaceId = this.workspaceId();
      if (workspaceId) {
        untracked(() => this.loadAutonomy());
      }
    });
    inject(DestroyRef).onDestroy(() => this.stopPolling());
    // L'historique suit le chantier choisi et sa version : relu si le plan change pendant qu'il est ouvert.
    effect(() => {
      const c = this.selected();
      const open = this.historyOpen();
      const version = c?.planVersion;
      if (open && c && version !== undefined) {
        untracked(() => this.loadHistory(c.number));
      }
    });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  select(number: number): void {
    this.chosen.set(number);
  }

  gestures(c: OpenChantier): number {
    return chantierGestures(c);
  }

  ringDash(c: OpenChantier): string {
    const ratio = c.total > 0 ? Math.min(1, c.done / c.total) : 0;
    return `${(ratio * RING).toFixed(1)} ${RING.toFixed(1)}`;
  }

  planLabel(c: OpenChantier): string {
    const plan = c.plan;
    if (!plan) {
      return 'pas encore de plan';
    }
    if (plan.awaitingValidation) {
      return plan.amendment ? `plan v${plan.version} · amendement à valider` : `plan v${plan.version} · à valider`;
    }
    return plan.validatedVersion === plan.version ? `plan v${plan.version} · validé` : `plan v${plan.version}`;
  }

  phaseState(c: OpenChantier, phase: JourneyPhase): 'past' | 'current' | 'todo' {
    const order = JOURNEY_PHASES.map((p) => p.phase);
    const current = c.phase ? order.indexOf(c.phase) : -1;
    const index = order.indexOf(phase);
    if (index === current) {
      return 'current';
    }
    return index < current ? 'past' : 'todo';
  }

  state(step: JourneyPlanStep) {
    return stepState(step);
  }

  icon(step: JourneyPlanStep): string {
    return stepStateIcon(stepState(step));
  }

  stateLabel(step: JourneyPlanStep): string {
    return stepStateLabel(stepState(step));
  }

  /** « après étape 4 · attente cert-gino » pour une étape bloquée ; `null` sinon. */
  blockedText(step: JourneyPlanStep): string | null {
    if (step.status === 'BLOQUE') {
      return step.evidence ? `bloquée : ${step.evidence}` : 'bloquée par l’agent';
    }
    if (stepState(step) !== 'blocked' || !step.blockedBy?.length) {
      return null;
    }
    const steps = step.blockedBy.filter((b) => b.startsWith('étape ')).map((b) => b.slice('étape '.length));
    const waits = step.blockedBy.filter((b) => !b.startsWith('étape '));
    const parts: string[] = [];
    if (steps.length) {
      parts.push(`après ${steps.length > 1 ? 'les étapes' : 'l’étape'} ${steps.join(', ')}`);
    }
    parts.push(...waits);
    return parts.join(' · ');
  }

  subDone(step: JourneyPlanStep): number {
    return (step.subSteps ?? []).filter((s) => s.done).length;
  }

  /** Dépliée : choix explicite de l'utilisateur, sinon l'étape en cours. */
  isOpen(c: OpenChantier, index: number): boolean {
    const explicit = this.manual()[`${c.number}:${index}`];
    if (explicit !== undefined) {
      return explicit;
    }
    return currentStepIndex(c.plan) === index;
  }

  toggle(c: OpenChantier, index: number): void {
    const key = `${c.number}:${index}`;
    const next = !this.isOpen(c, index);
    this.manual.update((m) => ({ ...m, [key]: next }));
  }

  key(c: OpenChantier, index: number): string {
    return `${c.number}:${index}`;
  }

  /** Une étape qu'on peut lancer : à faire ou en échec. */
  doable(step: JourneyPlanStep): boolean {
    return step.status === 'A_FAIRE' || step.status === 'ECHEC';
  }

  waitOpen(step: JourneyPlanStep): boolean {
    return !!step.waitsOn && (step.waitsOnStatus === 'A_FAIRE' || step.waitsOnStatus === 'DEMANDE');
  }

  hasProof(step: JourneyPlanStep): boolean {
    return !!step.evidence || (step.subSteps ?? []).some((s) => !!s.evidence);
  }

  hasActions(c: OpenChantier, step: JourneyPlanStep): boolean {
    return (this.canSend() && (this.doable(step) || this.waitOpen(step))) || this.hasProof(step);
  }

  /** Pourquoi « Faire cette étape » est inactif, ou `null` s'il est actif. */
  doBlockedReason(c: OpenChantier, step: JourneyPlanStep): string | null {
    if (!c.executing) {
      return c.plan?.awaitingValidation ? 'plan à valider d’abord' : 'le chantier n’est pas en exécution';
    }
    if (step.unlocked === false) {
      return `bloquée : ${this.blockedText(step) ?? 'dépendances à faire'}`;
    }
    return null;
  }

  /**
   * [Faire cette étape] : la demande part tout de suite, étape EXTERNE comprise — le plan validé vaut
   * feu vert, aucune confirmation de plus (F-218 / SF-218-03, mode offensif).
   */
  askDo(c: OpenChantier, index: number, step: JourneyPlanStep): void {
    if (this.doBlockedReason(c, step)) {
      return;
    }
    this.sendDo(c, index, step);
  }

  private sendDo(c: OpenChantier, index: number, step: JourneyPlanStep): void {
    this.request.emit(`Chantier ${c.number} — fais l’étape ${index + 1} : ${step.title}`);
  }

  /** [Relancer l'attente] : la demande de relance, visible dans le fil. */
  remind(c: OpenChantier, index: number, step: JourneyPlanStep): void {
    this.request.emit(`Chantier ${c.number} — relance l’attente « ${step.waitsOn} » dont dépend l’étape ${index + 1} : ${step.title}`);
  }

  /** [Voir la preuve] : l'étape se déplie et sa preuve est surlignée, amenée en vue. */
  seeProof(c: OpenChantier, index: number): void {
    const key = this.key(c, index);
    this.manual.update((m) => ({ ...m, [key]: true }));
    this.proofShown.set(key);
    queueMicrotask(() => {
      const el = this.host.nativeElement.querySelector('.board__proof--shown') as HTMLElement | null;
      el?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
    });
  }

  // ------------------------------------------------------------------ F-219 : chantier en autonome

  /** L'autonomie du chantier N, ou `null`. */
  autonomyOf(number: number): AutonomyBoardEntry | null {
    return this.autonomy()?.chantiers.find((e) => e.number === number) ?? null;
  }

  /** Vrai si le chantier N tourne en autonome. */
  running(number: number): boolean {
    return this.autonomyOf(number)?.run?.status === 'RUNNING';
  }

  autoState(entry: AutonomyBoardEntry): 'running' | 'ready' | 'advance' | 'not-ready' {
    if (entry.run?.status === 'RUNNING') {
      return 'running';
    }
    if (entry.preflight.ready) {
      return 'ready';
    }
    return entry.preflight.advance?.possible ? 'advance' : 'not-ready';
  }

  /** « En autonome — étape 3/7 » ou « Jusqu'au blocage — étape 3/7 » (SF-219-05). */
  runningLabel(entry: AutonomyBoardEntry, c: OpenChantier): string {
    return autonomyRunningLabel(entry.run ?? {}, this.currentStep(c), c.total);
  }

  /** Ce qui restera, groupé par « Ça attend votre décision » et « On attend quelqu'un » (SF-219-05). */
  remainingGroups(entry: AutonomyBoardEntry) {
    return groupRemaining(entry.preflight.advance);
  }

  currentStep(c: OpenChantier): number {
    return autonomyCurrentStep(c.done, c.total);
  }

  outcome(status: string): string {
    return autonomyOutcomeLabel(status);
  }

  /**
   * [Lancer en autonome] : un clic, aucune confirmation (F-218). `UNTIL_BLOCKED` : [Avancer jusqu'au blocage]
   * (SF-219-05).
   */
  launch(c: OpenChantier, mode: AutonomyMode = 'STRICT'): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId || this.busy()) {
      return;
    }
    // F-221 / SF-221-04 : lancer UN AUTRE chantier que celui du volet passe par l'overlay « en parallèle ».
    const shown = this.shownChantier();
    if (shown !== null && c.number !== shown) {
      this.parallel.emit({ number: c.number, mode });
      return;
    }
    this.busy.set(true);
    this.autonomyApi.launch(workspaceId, c.number, mode).subscribe({
      next: () => {
        this.busy.set(false);
        const what = mode === 'UNTIL_BLOCKED' ? 'avance jusqu’au blocage' : 'lancé en autonome';
        this.snackBar.open(`Chantier ${c.number} ${what}. Vous pouvez quitter l’écran.`, 'OK', { duration: 5000 });
        this.loadAutonomy();
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.snackBar.open(this.conflictMessage(err, 'Le chantier n’a pas pu partir en autonome.'), 'OK', { duration: 8000 });
        this.loadAutonomy();
      },
    });
  }

  /**
   * [Planifier] sur la carte (F-176 / SF-176-12) : le diagnostic du chantier N est accepté sans changer le focus,
   * puis la demande visible relance l'agent pour poser le plan de CE chantier.
   */
  planify(c: OpenChantier): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.journeys.confirmDiagnosis(workspaceId, c.number).subscribe({
      next: () => {
        this.busy.set(false);
        this.changed.emit();
        this.request.emit(`✓ Chantier ${c.number} : diagnostic confirmé — pose le plan de ce chantier.`);
      },
      error: () => {
        this.busy.set(false);
        this.snackBar.open('Le diagnostic n’a pas pu être confirmé.', 'OK', { duration: 5000 });
        this.changed.emit();
      },
    });
  }

  /** « En Exécution sans activité depuis 6 jours » (F-222 / SF-222-03). */
  staleLabel(c: OpenChantier): string {
    const days = idleDaysOf(c);
    return days === null ? 'En Exécution sans activité depuis plusieurs jours'
      : `En Exécution sans activité depuis ${days} jour${days > 1 ? 's' : ''}`;
  }

  /** [Clore] un chantier en sommeil (F-222 / SF-222-03) : un geste de l'utilisateur, jamais automatique. */
  closeStale(c: OpenChantier): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.journeys.closeChantier(workspaceId, c.number).subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open(`Chantier ${c.number} clos.`, 'OK', { duration: 4000 });
        this.changed.emit();
      },
      error: () => {
        this.busy.set(false);
        this.snackBar.open('Le chantier n’a pas pu être clos.', 'OK', { duration: 5000 });
        this.changed.emit();
      },
    });
  }

  /** [Reprendre la main] : la course s'arrête à la frontière sûre suivante. */
  takeBack(c: OpenChantier): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.autonomyApi.stop(workspaceId, c.number).subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open(`Vous avez repris la main sur le chantier ${c.number}.`, 'OK', { duration: 5000 });
        this.loadAutonomy();
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.snackBar.open(this.conflictMessage(err, 'La reprise en main a échoué.'), 'OK', { duration: 8000 });
        this.loadAutonomy();
      },
    });
  }

  private conflictMessage(err: HttpErrorResponse, fallback: string): string {
    const body = err?.error as AutonomyConflict | null;
    if (!body?.message) {
      return fallback;
    }
    const first = body.reasons?.[0]?.label;
    return first ? `${body.message} ${first}` : body.message;
  }

  private loadAutonomy(): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    this.autonomyApi.board(workspaceId).subscribe({
      next: (board) => {
        this.autonomy.set(board);
        const anyRunning = (board?.chantiers ?? []).some((e) => e.run?.status === 'RUNNING');
        if (anyRunning) {
          this.startPolling();
        } else {
          this.stopPolling();
        }
      },
      error: () => {
        this.autonomy.set(null);
        this.stopPolling();
      },
    });
  }

  private startPolling(): void {
    if (this.poll === null) {
      this.poll = setInterval(() => this.loadAutonomy(), 5000);
    }
  }

  private stopPolling(): void {
    if (this.poll !== null) {
      clearInterval(this.poll);
      this.poll = null;
    }
  }

  toggleHistory(): void {
    this.historyOpen.update((open) => !open);
  }

  private loadHistory(number: number): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      this.versions.set([]);
      return;
    }
    this.historyState.set('loading');
    this.journeys.versions(workspaceId, number).subscribe({
      next: (versions) => {
        if (this.selected()?.number === number) {
          this.versions.set(versions ?? []);
          this.historyState.set('ok');
        }
      },
      error: () => this.historyState.set('error'),
    });
  }
}
