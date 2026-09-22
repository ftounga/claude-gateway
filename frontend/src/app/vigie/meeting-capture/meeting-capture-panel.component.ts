import { DatePipe } from '@angular/common';
import {
  Component,
  Input,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';

import { CreateMeetingRequest, TeamsMeeting } from '../../core/models/teams-meeting.models';
import { TeamsMeetingService } from '../../core/services/teams-meeting.service';
import { httpErrorMessage } from '../../shared/http-error.util';
import {
  JoinAndCaptureDialogComponent,
  JoinAndCaptureDialogData,
} from './join-and-capture-dialog.component';

/**
 * Le panneau « Réunions » de la Vigie (F-128 / SF-128-01) : « Rejoindre & capturer », l'indicateur
 * « capture en cours » (qui ne ment jamais), le rappel de consentement et la rétention, l'arrêt et la
 * pause, puis la liste des réunions passées. Aucun appel HTTP direct : tout passe par
 * {@link TeamsMeetingService}.
 */
@Component({
  selector: 'app-meeting-capture-panel',
  standalone: true,
  imports: [
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    RouterLink,
  ],
  template: `
    <div class="meetings">
      <header class="meetings__head">
        <div>
          <h3 class="meetings__title">Les réunions de {{ hostName }}</h3>
          <p class="meetings__lede">
            Rejoignez la réunion dans le Chrome managé ; une fois « en réunion », démarrez
            l'enregistrement — elle devient un compte rendu exploitable (résumé, décisions, actions).
          </p>
        </div>
        <button
          mat-flat-button
          color="primary"
          type="button"
          class="meetings__join"
          [disabled]="busy()"
          (click)="openJoin()"
        >
          <mat-icon>videocam</mat-icon>
          Rejoindre
        </button>
      </header>

      @if (loading()) {
        <div class="meetings__loading"><mat-spinner diameter="28"></mat-spinner></div>
      } @else if (error()) {
        <p class="meetings__error">{{ error() }}</p>
      } @else {
        @if (joined().length > 0) {
          <section class="meetings__joined" aria-label="Réunions rejointes">
            @for (m of joined(); track m.id) {
              <article class="capture capture--joined">
                <div class="capture__bar capture__bar--joined">
                  <span class="capture__rec">
                    <mat-icon aria-hidden="true" class="capture__state-icon">{{
                      m.inCall ? 'check_circle' : 'hourglass_top'
                    }}</mat-icon>
                    {{ m.inCall ? 'En réunion' : 'Réunion à confirmer' }}
                  </span>
                  <span class="capture__since">Rejointe à {{ m.startedAt | date: 'HH:mm' }}</span>
                </div>
                <div class="capture__body">
                  <p class="capture__name">{{ m.title || 'Réunion sans titre' }}</p>
                  @if (!m.inCall) {
                    <p class="capture__hint">
                      La réunion n'est pas encore confirmée « en cours » sur ce poste. Ouvrez la Vigie
                      pour vérifier, ou abandonnez puis relancez « Rejoindre ».
                    </p>
                  }
                  <p class="capture__consent">
                    <mat-icon aria-hidden="true">lock</mat-icon>
                    L'enregistrement démarrera à votre commande. Conservation : {{ m.retentionDays }} jours.
                  </p>
                  <div class="capture__actions">
                    <button
                      mat-flat-button
                      color="primary"
                      type="button"
                      [disabled]="busy() || !m.inCall"
                      (click)="startCapture(m)"
                    >
                      <mat-icon>fiber_manual_record</mat-icon>
                      Démarrer l'enregistrement
                    </button>
                    <button mat-stroked-button type="button" [disabled]="busy()" (click)="stop(m)">
                      Abandonner
                    </button>
                  </div>
                </div>
              </article>
            }
          </section>
        }

        @if (live().length > 0) {
          <section class="meetings__live" aria-label="Captures en cours">
            @for (m of live(); track m.id) {
              <article class="capture">
                <div class="capture__bar">
                  <span class="capture__rec">
                    <span class="capture__dot" [class.capture__dot--paused]="m.state === 'PAUSED'"></span>
                    {{ m.state === 'PAUSED' ? 'En pause' : 'Capture en cours' }}
                  </span>
                  <span class="capture__since">Depuis {{ m.startedAt | date: 'HH:mm' }}</span>
                </div>
                <div class="capture__body">
                  <p class="capture__name">{{ m.title || 'Réunion sans titre' }}</p>
                  <div class="capture__chips">
                    <span class="chip chip--on">Audio réunion</span>
                    <span class="chip chip--on">Micro</span>
                    <span class="chip chip--on">Slides (images clés)</span>
                    <span class="chip chip--off">Vidéo pleine</span>
                  </div>
                  <p class="capture__consent">
                    <mat-icon aria-hidden="true">lock</mat-icon>
                    Vous enregistrez votre réunion. Conservation : {{ m.retentionDays }} jours.
                  </p>
                  <div class="capture__actions">
                    <button mat-flat-button color="warn" type="button" [disabled]="busy()" (click)="stop(m)">
                      Arrêter
                    </button>
                    @if (m.state === 'RECORDING') {
                      <button mat-stroked-button type="button" [disabled]="busy()" (click)="pause(m)">Pause</button>
                    } @else {
                      <button mat-stroked-button type="button" [disabled]="busy()" (click)="resume(m)">Reprendre</button>
                    }
                  </div>
                </div>
              </article>
            }
          </section>
        }

        @if (toFile().length > 0) {
          <!-- F-147 / SF-147-03 : rappelé tant que ce n'est pas fait — le savoir n'entre dans la boucle
               que si quelqu'un range, et personne n'y pense sans rappel. -->
          <p class="meetings__to-file" role="status">
            <mat-icon aria-hidden="true">inventory_2</mat-icon>
            {{ toFile().length }} réunion(s) attendent d'être rangées dans la carte du poste.
          </p>
        }

        @if (past().length > 0) {
          <section class="meetings__past" aria-label="Réunions passées">
            <h4 class="meetings__subtitle">Réunions capturées</h4>
            <!-- SF-128-15 : liste défilante bornée — hauteur max + scroll interne, la page ne s'étire plus. -->
            <div class="meetings__past-list">
              @for (m of past(); track m.id) {
                <a
                  class="past-row"
                  [routerLink]="['/vigie', hostId, 'reunions', m.id]"
                  queryParamsHandling="preserve"
                >
                  <mat-icon aria-hidden="true">event_available</mat-icon>
                  <span class="past-row__name">{{ m.title || 'Réunion sans titre' }}</span>
                  <span class="past-row__when">{{ m.startedAt | date: 'd MMM, HH:mm' }}</span>
                  <span class="past-row__state">{{ m.state === 'FAILED' ? 'Échec' : 'Terminée' }}</span>
                  <mat-icon class="past-row__go" aria-hidden="true">chevron_right</mat-icon>
                </a>
              }
            </div>
          </section>
        }

        @if (joined().length === 0 && live().length === 0 && past().length === 0) {
          <p class="meetings__empty">
            Aucune réunion capturée pour l'instant. Lancez « Rejoindre » depuis une réunion Teams.
          </p>
        }
      }
    </div>
  `,
  styles: [
    `
      .meetings {
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-4, 24px);
      }
      .meetings__head {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--cg-space-3, 16px);
        flex-wrap: wrap;
      }
      .meetings__title {
        margin: 0 0 var(--cg-space-1, 4px);
        font-family: var(--cg-font-heading, 'Space Grotesk', sans-serif);
        color: var(--cg-primary, #1a3a5c);
      }
      .meetings__lede {
        margin: 0;
        color: var(--cg-text-secondary, #6b7a8d);
        max-width: 60ch;
      }
      .meetings__loading {
        display: flex;
        justify-content: center;
        padding: var(--cg-space-5, 32px);
      }
      .meetings__error {
        color: var(--cg-error, #d32f2f);
      }
      .capture {
        border: 1px solid var(--cg-divider, #e0e4ea);
        border-radius: 16px;
        overflow: hidden;
        background: var(--cg-surface, #fff);
      }
      .capture__bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--cg-space-2, 8px) var(--cg-space-3, 16px);
        background: var(--cg-primary, #1a3a5c);
        color: #fff;
      }
      .capture__rec {
        display: inline-flex;
        align-items: center;
        gap: var(--cg-space-2, 8px);
        font-weight: 600;
        font-size: 13px;
      }
      .capture__dot {
        width: 10px;
        height: 10px;
        border-radius: 50%;
        background: var(--cg-error, #d8402e);
        animation: capture-pulse 1.6s infinite;
      }
      .capture__dot--paused {
        background: var(--cg-accent, #c9973a);
        animation: none;
      }
      /* SF-128-16 : réunion rejointe, en attente de « Démarrer l'enregistrement ». */
      .capture__bar--joined {
        background: var(--cg-accent, #c9973a);
      }
      .capture__state-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
      .capture__hint {
        margin: 0;
        font-size: 12px;
        color: var(--cg-gold-ink, #8a5200);
      }
      @keyframes capture-pulse {
        0% {
          box-shadow: 0 0 0 0 rgba(216, 64, 46, 0.55);
        }
        70% {
          box-shadow: 0 0 0 7px rgba(216, 64, 46, 0);
        }
        100% {
          box-shadow: 0 0 0 0 rgba(216, 64, 46, 0);
        }
      }
      .capture__since {
        font-family: var(--cg-font-mono, monospace);
        font-size: 12px;
        opacity: 0.85;
      }
      .capture__body {
        padding: var(--cg-space-3, 16px);
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-2, 8px);
      }
      .capture__name {
        margin: 0;
        font-weight: 600;
      }
      .capture__chips {
        display: flex;
        flex-wrap: wrap;
        gap: var(--cg-space-1, 4px);
      }
      .chip {
        font-size: 12px;
        padding: 4px 10px;
        border-radius: 999px;
        border: 1px solid var(--cg-divider, #e0e4ea);
        color: var(--cg-text-secondary, #6b7a8d);
      }
      .chip--on {
        background: var(--cg-surface-2, #eef1f6);
        color: var(--cg-primary, #1a3a5c);
      }
      .chip--off {
        opacity: 0.6;
      }
      .capture__consent {
        display: flex;
        align-items: center;
        gap: var(--cg-space-1, 4px);
        margin: 0;
        font-size: 12px;
        color: var(--cg-gold-ink, #8a5200);
      }
      .capture__consent mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }
      .capture__actions {
        display: flex;
        gap: var(--cg-space-2, 8px);
        margin-top: var(--cg-space-1, 4px);
      }
      .meetings__subtitle {
        margin: 0 0 var(--cg-space-2, 8px);
        color: var(--cg-text-secondary, #6b7a8d);
        font-size: 13px;
        text-transform: uppercase;
        letter-spacing: 0.06em;
      }
      /* SF-128-15 : liste des réunions passées défilante bornée — la page ne s'étire plus. */
      .meetings__past-list {
        max-height: 320px;
        overflow-y: auto;
        overflow-x: hidden;
      }
      .past-row {
        display: flex;
        align-items: center;
        gap: var(--cg-space-2, 8px);
        padding: var(--cg-space-2, 8px);
        margin: 0 calc(-1 * var(--cg-space-2, 8px));
        border-top: 1px solid var(--cg-divider, #e0e4ea);
        border-radius: 8px;
        color: inherit;
        text-decoration: none;
        cursor: pointer;
      }
      .past-row:hover {
        background: var(--cg-surface-2, #eef1f6);
      }
      .past-row__name {
        flex: 1;
        font-weight: 500;
      }
      .past-row__go {
        color: var(--cg-text-secondary, #6b7a8d);
      }
      .past-row__when,
      .past-row__state {
        color: var(--cg-text-secondary, #6b7a8d);
        font-size: 12px;
      }
      .meetings__empty {
        color: var(--cg-text-secondary, #6b7a8d);
      }

      .meetings__to-file {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0;
        color: var(--cg-text-primary, #1c2b3a);
        font-size: 14px;
      }
    `,
  ],
})
export class MeetingCapturePanelComponent implements OnInit, OnDestroy {
  @Input({ required: true }) hostId!: string;
  @Input() hostName = 'ce poste';

  private readonly service = inject(TeamsMeetingService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly meetings = signal<TeamsMeeting[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  /** SF-128-16 : réunions rejointes, en attente de « Démarrer l'enregistrement ». */
  readonly joined = computed(() => this.meetings().filter((m) => m.state === 'JOINED'));
  readonly live = computed(() => this.meetings().filter((m) => m.state === 'RECORDING' || m.state === 'PAUSED'));
  readonly past = computed(() => this.meetings().filter((m) => m.state === 'STOPPED' || m.state === 'FAILED'));
  /**
   * **Le rappel** (F-147 / SF-147-03) : les réunions qui portent du texte et dont les faits durables
   * ne sont pas encore rangés dans la carte du poste. Calculé sur la liste **déjà chargée** — une
   * route de plus ne dirait rien que celle-ci ne dise déjà.
   */
  readonly toFile = computed(() => this.meetings().filter(
    (m) => m.cardPromotedAt === null && (m.hasTranscript || m.hasExternalTranscript)));

  ngOnInit(): void {
    this.reload();
  }

  ngOnDestroy(): void {
    // Rien à nettoyer : pas d'abonnement long ni de timer.
  }

  openJoin(): void {
    const data: JoinAndCaptureDialogData = { hostName: this.hostName };
    this.dialog
      .open(JoinAndCaptureDialogComponent, { data, autoFocus: false })
      .afterClosed()
      .subscribe((request: CreateMeetingRequest | null) => {
        if (request) {
          this.create(request);
        }
      });
  }

  startCapture(meeting: TeamsMeeting): void {
    this.run(this.service.startCapture(this.hostId, meeting.id), 'Enregistrement démarré.');
  }

  stop(meeting: TeamsMeeting): void {
    this.run(this.service.stop(this.hostId, meeting.id), 'Capture arrêtée.');
  }

  pause(meeting: TeamsMeeting): void {
    this.run(this.service.pause(this.hostId, meeting.id), 'Capture en pause.');
  }

  resume(meeting: TeamsMeeting): void {
    this.run(this.service.resume(this.hostId, meeting.id), 'Capture reprise.');
  }

  private create(request: CreateMeetingRequest): void {
    this.run(this.service.create(this.hostId, request), 'Réunion rejointe. Démarrez l\'enregistrement une fois « en réunion ».');
  }

  private run(source: import('rxjs').Observable<TeamsMeeting>, success: string): void {
    this.busy.set(true);
    source.subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open(success, 'OK', { duration: 4000 });
        this.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.snackBar.open(
          httpErrorMessage(err, "L'action sur la réunion a échoué."),
          'Fermer',
          { duration: 6000 },
        );
      },
    });
  }

  private reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.list(this.hostId).subscribe({
      next: (meetings) => {
        this.meetings.set(meetings);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(httpErrorMessage(err, 'Les réunions n\'ont pas pu être lues.'));
      },
    });
  }
}
