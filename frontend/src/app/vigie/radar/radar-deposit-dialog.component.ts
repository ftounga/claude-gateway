import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { RadarDepositDone } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import {
  depositErrorOf,
  depositFileProblem,
  isoWithOffset,
  localInputValue,
  sendInChunks,
  titleFromFileName,
} from './radar-deposit';

/** Ce que le dialogue reçoit. */
export interface RadarDepositDialogData {
  hostId: string;
}

type DepositPhase = 'form' | 'sending' | 'done';

/**
 * **Déposer un enregistrement** (F-104 / SF-104-04) : un enregistrement hors Teams (téléphone, salle, autre
 * visio) part **par morceaux** sur la machine du client, dans le dossier de dépôt du Radar, avec son titre et sa
 * date. Il y sera transcrit à la prochaine synchro ; seul le texte remonte. La gateway ne le garde jamais.
 */
@Component({
  selector: 'app-radar-deposit-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>Déposer un enregistrement</h2>
    <mat-dialog-content class="radar-deposit">
      @switch (phase()) {
        @case ('form') {
          <p class="radar-deposit__lead">
            Le fichier va sur la machine du client ; il y est transcrit à la prochaine synchro, seul le texte remonte.
          </p>
          <input #picker type="file" class="radar-deposit__picker" [accept]="accept" (change)="pick(picker.files)" />
          <div class="radar-deposit__file">
            <button mat-stroked-button type="button" (click)="picker.click()">
              <mat-icon>mic</mat-icon>
              Choisir un fichier
            </button>
            @if (file(); as f) {
              <span class="radar-deposit__name">{{ f.name }} — {{ sizeLabel(f.size) }}</span>
            }
          </div>
          @if (fileProblem(); as problem) {
            <p class="radar-deposit__error" role="alert">{{ problem }}</p>
          }
          <mat-form-field appearance="outline">
            <mat-label>Titre de la réunion</mat-label>
            <input matInput name="title" maxlength="200" required [ngModel]="title()" (ngModelChange)="title.set($event)" />
            @if (title().trim().length === 0) {
              <mat-error>Le titre est requis.</mat-error>
            }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Date et heure de la réunion</mat-label>
            <input matInput type="datetime-local" name="recordedAt" required [ngModel]="recordedAt()"
              (ngModelChange)="recordedAt.set($event)" />
          </mat-form-field>
        }
        @case ('sending') {
          <p class="radar-deposit__lead">Envoi sur le poste… {{ percent() }} %</p>
          <mat-progress-bar mode="determinate" [value]="percent()"></mat-progress-bar>
        }
        @case ('done') {
          <p class="radar-deposit__lead">
            Déposé sur le poste : « {{ done()?.title }} ». Il sera transcrit sur la machine à la prochaine synchro.
          </p>
        }
      }
      @if (error(); as message) {
        <p class="radar-deposit__error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @switch (phase()) {
        @case ('form') {
          <button mat-button type="button" (click)="close()">Annuler</button>
          <button mat-flat-button color="primary" type="button" [disabled]="!canSend()" (click)="send()">
            Déposer sur le poste
          </button>
        }
        @case ('sending') {
          <button mat-button type="button" (click)="cancel()">Annuler le dépôt</button>
        }
        @case ('done') {
          <button mat-flat-button color="primary" type="button" (click)="close()">Fermer</button>
        }
      }
    </mat-dialog-actions>
  `,
  styles: `
    .radar-deposit {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      min-width: min(480px, 80vw);
    }

    .radar-deposit__lead {
      margin: 0;
      font-size: 14px;
      color: var(--cg-text-secondary);
    }

    .radar-deposit__picker {
      display: none;
    }

    .radar-deposit__file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .radar-deposit__name {
      font-size: 14px;
      color: var(--cg-text-primary);
      word-break: break-all;
    }

    .radar-deposit__error {
      margin: 0;
      font-size: 14px;
      color: var(--cg-error);
    }
  `,
})
export class RadarDepositDialogComponent {
  private readonly radar = inject(RadarService);
  private readonly dialogRef = inject(MatDialogRef<RadarDepositDialogComponent, RadarDepositDone | undefined>);
  private readonly data = inject<RadarDepositDialogData>(MAT_DIALOG_DATA);

  readonly accept = 'audio/*,video/*,.m4a,.mkv';
  readonly phase = signal<DepositPhase>('form');
  readonly file = signal<File | null>(null);
  readonly fileProblem = signal<string | null>(null);
  readonly title = signal('');
  readonly recordedAt = signal('');
  readonly percent = signal(0);
  readonly error = signal<string | null>(null);
  readonly done = signal<RadarDepositDone | null>(null);

  private uploadId: string | null = null;
  private cancelled = false;

  pick(files: FileList | null): void {
    const file = files && files.length > 0 ? files[0] : null;
    this.choose(file);
  }

  /** Un fichier choisi : il est vérifié, son titre et sa date sont proposés. */
  choose(file: File | null): void {
    this.error.set(null);
    this.file.set(file);
    this.fileProblem.set(file ? depositFileProblem(file) : null);
    if (file) {
      if (this.title().trim().length === 0) {
        this.title.set(titleFromFileName(file.name));
      }
      if (!this.recordedAt()) {
        this.recordedAt.set(localInputValue(file.lastModified || Date.now()));
      }
    }
  }

  canSend(): boolean {
    const file = this.file();
    return !!file && depositFileProblem(file) === null && this.title().trim().length > 0
      && isoWithOffset(this.recordedAt()) !== null;
  }

  async send(): Promise<void> {
    const file = this.file();
    const recordedAt = isoWithOffset(this.recordedAt());
    if (!file || !recordedAt || !this.canSend()) {
      return;
    }
    const hostId = this.data.hostId;
    this.error.set(null);
    this.cancelled = false;
    this.phase.set('sending');
    this.percent.set(0);
    try {
      const opened = await new Promise<{ uploadId: string; chunkBytes: number }>((resolve, reject) =>
        this.radar.openDeposit(hostId, file.name, file.size, this.title().trim(), recordedAt)
          .subscribe({ next: resolve, error: reject }));
      this.uploadId = opened.uploadId;
      const complete = await sendInChunks(file, opened.chunkBytes,
        (offset, chunk) => this.radar.sendDepositChunk(hostId, opened.uploadId, offset, chunk),
        (sent) => this.percent.set(Math.floor((sent * 100) / file.size)),
        () => this.cancelled);
      if (!complete) {
        return;
      }
      const done = await new Promise<RadarDepositDone>((resolve, reject) =>
        this.radar.finishDeposit(hostId, opened.uploadId).subscribe({ next: resolve, error: reject }));
      this.uploadId = null;
      this.done.set(done);
      this.phase.set('done');
    } catch (err) {
      this.abortQuietly();
      this.phase.set('form');
      this.error.set(depositErrorOf(err));
    }
  }

  cancel(): void {
    this.cancelled = true;
    this.abortQuietly();
    this.phase.set('form');
    this.percent.set(0);
  }

  close(): void {
    this.dialogRef.close(this.done() ?? undefined);
  }

  sizeLabel(bytes: number): string {
    return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} Mo` : `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }

  private abortQuietly(): void {
    const uploadId = this.uploadId;
    this.uploadId = null;
    if (uploadId) {
      this.radar.abortDeposit(this.data.hostId, uploadId).subscribe({ error: () => undefined });
    }
  }
}
