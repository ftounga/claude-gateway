import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarVerification } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RUNNER_HOST_PLATFORM } from '../../atelier/runner/runner-pairing-dialog.component';
import { CopyBlockComponent } from '../../chat/copy-block/copy-block.component';
import { CopyBlock } from '../../shared/copy-block.model';
import {
  Remedy,
  RemedyCommand,
  VERIFICATION_POLL_MS,
  refusalRemedy,
  verificationLines,
} from './radar-verification';

export interface RadarVerificationDialogData {
  hostId: string;
  hostName: string;
}

/**
 * **La vérification guidée** (F-100 / SF-100-06) — ce que le runner voit vraiment de la session Microsoft
 * d'un client, pendant que l'utilisateur ouvre un fil, une réunion passée et sa transcription dans la
 * fenêtre Chrome de Teams.
 *
 * <p><b>Des appels enchaînés</b> : un appel à la fois (le runner peut mettre 20 s à répondre), le suivant
 * cinq secondes après la fin du précédent, tant que tout n'est pas vu et qu'aucun refus ne bloque. Rien
 * ne part plus une fois le dialogue fermé.</p>
 *
 * <p>Aucune adresse n'est demandée : ce qui change d'un client à l'autre, c'est la session et les droits
 * (SF-100-01).</p>
 */
@Component({
  selector: 'app-radar-verification-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule, CopyBlockComponent],
  template: `
    <h2 mat-dialog-title>Ce que le runner voit de « {{ data.hostName }} »</h2>
    <mat-dialog-content class="verification">
      <p class="verification__lead">
        Dans la fenêtre Chrome de Teams reliée au runner, suivez les étapes : chaque case se coche dès que le
        runner a vu passer l'élément. Il n'ouvre rien à votre place et ne retient que des compteurs.
      </p>

      <ol class="verification__lines">
        @for (line of lines(); track line.key) {
          <li class="verification__line" [attr.data-key]="line.key" [attr.data-state]="line.state"
            [class.verification__line--remedy]="line.state === 'remedy'">
            <mat-icon class="verification__icon" [class.verification__icon--seen]="line.state === 'seen'"
              [attr.aria-label]="stateLabel(line.state)" role="img">
              {{ line.state === 'seen' ? 'check_circle' : line.state === 'remedy' ? 'error_outline' : 'radio_button_unchecked' }}
            </mat-icon>
            <div class="verification__body">
              <span class="verification__title">{{ line.title }}</span>
              @if (line.sentence) {
                <span class="verification__sentence">{{ line.sentence }}</span>
              }
              @if (line.remedy; as remedy) {
                <p class="verification__remedy">{{ remedy.text }}</p>
                @for (command of remedy.commands; track command.content) {
                  <app-copy-block class="verification__command" [block]="block(command)"></app-copy-block>
                }
              }
            </div>
          </li>
        }
      </ol>

      @if (refusal(); as remedy) {
        <div class="verification__refusal" role="alert">
          <p class="verification__remedy">{{ remedy.text }}</p>
          @for (command of remedy.commands; track command.content) {
            <app-copy-block class="verification__command" [block]="block(command)"></app-copy-block>
          }
        </div>
      } @else if (verification()?.complete) {
        <p class="verification__complete" role="status">
          <mat-icon aria-hidden="true">verified</mat-icon>
          Le runner voit tout ce que le Radar lira.
        </p>
      } @else {
        <p class="verification__progress" role="status">
          @if (busy()) {
            <mat-spinner diameter="16"></mat-spinner>
            Le runner regarde…
          } @else {
            <mat-icon aria-hidden="true">schedule</mat-icon>
            Nouvelle vérification dans quelques secondes.
          }
        </p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" class="verification__restart" [disabled]="busy()" (click)="restart()">
        Recommencer
      </button>
      @if (refusal()) {
        <button mat-stroked-button type="button" class="verification__retry" [disabled]="busy()" (click)="retry()">
          Réessayer
        </button>
      }
      <button mat-flat-button color="primary" type="button" class="verification__close" (click)="close()">
        {{ verification()?.complete ? 'Fermer' : 'Plus tard' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .verification__lead {
      margin: 0 0 var(--cg-space-3);
      color: var(--cg-text-secondary);
    }

    .verification__lines {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
    }

    .verification__line {
      display: flex;
      align-items: flex-start;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2);
      border-left: 4px solid transparent;
    }

    /* §12 : ce qui réclame un geste entre par un filet ambre, jamais par un fond. */
    .verification__line--remedy {
      border-left-color: #F9A825;
    }

    .verification__icon {
      flex: none;
      color: var(--cg-text-secondary);
    }

    .verification__icon--seen {
      color: var(--cg-success);
    }

    .verification__body {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      min-width: 0;
    }

    .verification__title {
      font-weight: 600;
    }

    .verification__sentence {
      color: var(--cg-text-secondary);
      font-size: 14px;
      white-space: pre-line;
    }

    .verification__remedy {
      margin: 0;
      font-size: 14px;
    }

    .verification__refusal {
      margin-top: var(--cg-space-3);
      padding: var(--cg-space-2) var(--cg-space-3);
      border-left: 4px solid #F9A825;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
    }

    .verification__complete,
    .verification__progress {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin: var(--cg-space-3) 0 0;
      font-size: 14px;
    }

    .verification__complete mat-icon {
      color: var(--cg-success);
    }

    .verification__progress {
      color: var(--cg-text-secondary);
    }
  `,
})
export class RadarVerificationDialogComponent implements OnInit {
  static readonly DIALOG_WIDTH = '680px';

  readonly data = inject<RadarVerificationDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<RadarVerificationDialogComponent>);
  private readonly radar = inject(RadarService);
  private readonly platform = inject(RUNNER_HOST_PLATFORM);
  private readonly origin = inject(DOCUMENT).location?.origin ?? '';
  private readonly destroyRef = inject(DestroyRef);

  readonly verification = signal<RadarVerification | null>(null);
  readonly busy = signal(false);
  readonly refusal = signal<Remedy | null>(null);

  readonly lines = computed(() => verificationLines(this.verification(), this.platform, this.origin));

  private timer: ReturnType<typeof setTimeout> | null = null;
  private call: Subscription | null = null;
  private closed = false;

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => this.stop());
    this.check();
  }

  stateLabel(state: string): string {
    return state === 'seen' ? 'Vu' : state === 'remedy' ? 'À faire' : 'En attente';
  }

  block(command: RemedyCommand): CopyBlock {
    return { type: 'code', language: null, title: command.title, content: command.content };
  }

  /** Recommence : les cases sont décochées, puis la vérification reprend. */
  restart(): void {
    if (this.busy()) {
      return;
    }
    this.stop();
    this.closed = false;
    this.busy.set(true);
    this.refusal.set(null);
    this.call = this.radar.resetVerification(this.data.hostId).subscribe({
      next: (verification) => {
        this.busy.set(false);
        this.verification.set(verification);
        this.check();
      },
      error: (err: unknown) => this.refuse(err),
    });
  }

  /** Après un refus : un nouvel essai. */
  retry(): void {
    if (this.busy()) {
      return;
    }
    this.refusal.set(null);
    this.check();
  }

  close(): void {
    this.stop();
    this.dialogRef.close(this.verification()?.complete === true);
  }

  private check(): void {
    if (this.closed) {
      return;
    }
    this.busy.set(true);
    this.call = this.radar.verify(this.data.hostId).subscribe({
      next: (verification) => {
        this.busy.set(false);
        this.verification.set(verification);
        if (!verification.complete) {
          this.schedule();
        }
      },
      error: (err: unknown) => this.refuse(err),
    });
  }

  private refuse(err: unknown): void {
    this.busy.set(false);
    this.refusal.set(refusalRemedy(err, this.platform, this.origin));
  }

  private schedule(): void {
    if (this.closed) {
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      this.check();
    }, VERIFICATION_POLL_MS);
  }

  private stop(): void {
    this.closed = true;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.call?.unsubscribe();
    this.call = null;
  }
}
