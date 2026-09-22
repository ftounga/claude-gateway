import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { RadarDepositDone, RadarRecordingProgress } from '../../core/models/radar.models';
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

type DepositPhase = 'form' | 'sending' | 'transcribing' | 'done';

/**
 * **Déposer un enregistrement** (F-104 / SF-104-04) : un enregistrement hors Teams (téléphone, salle, autre
 * visio) part **par morceaux** sur la machine du client, dans le dossier de dépôt du Radar, avec son titre et sa
 * date. **Le poste le transcrit tout de suite** (F-147 / SF-147-01) et le dialogue dit où il en est, en toutes
 * lettres ; seul le texte remonte. La gateway ne garde jamais le fichier.
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
            Le fichier va sur la machine du client ; il y est transcrit aussitôt, sur place, et seul le texte remonte.
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
        @case ('transcribing') {
          <p class="radar-deposit__lead">
            Déposé sur le poste : « {{ done()?.title }} ». Transcription en cours, sur la machine.
          </p>
          <p class="radar-deposit__phase" role="status">{{ phaseLabel() }}</p>
          <mat-progress-bar mode="indeterminate"></mat-progress-bar>
        }
        @case ('done') {
          <p class="radar-deposit__lead">
            Déposé sur le poste : « {{ done()?.title }} ». {{ outcome() }}
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
        @case ('transcribing') {
          <button mat-button type="button" (click)="close()">Fermer</button>
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

    .radar-deposit__phase {
      margin: 0;
      font-size: 14px;
      color: var(--cg-text-primary);
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
  /** La phrase d'avancement **du poste**, recopiée telle quelle (F-147 / SF-147-01). */
  readonly phaseLabel = signal('');
  /** Ce qu'il est advenu de la transcription, une fois la phase terminée. */
  readonly outcome = signal('');

  /** Intervalle entre deux demandes d'avancement. */
  static readonly POLL_MS = 3000;
  /** Échecs d'affilée au-delà desquels on cesse de demander, et on le dit. */
  static readonly MAX_MISSES = 3;

  private uploadId: string | null = null;
  private cancelled = false;
  private watching: ReturnType<typeof setTimeout> | null = null;
  private misses = 0;

  constructor() {
    // Le dialogue fermé, plus une seule demande d'avancement : le travail, lui, continue sur le poste.
    inject(DestroyRef).onDestroy(() => this.stopWatching());
  }

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
      this.startTranscription(done, opened.uploadId);
    } catch (err) {
      this.abortQuietly();
      this.phase.set('form');
      this.error.set(depositErrorOf(err));
    }
  }

  /**
   * Le dépôt est fini : ou bien le poste a **commencé** à transcrire et on suit, ou bien il ne l'a pas
   * fait — et on le dit plutôt que de laisser croire que c'est en cours.
   */
  private startTranscription(done: RadarDepositDone, uploadId: string): void {
    if (done.transcription === 'started' && done.jobId) {
      this.phaseLabel.set(done.phaseLabel || 'Transcription en cours sur la machine.');
      this.phase.set('transcribing');
      this.watch(uploadId);
      return;
    }
    this.outcome.set(done.transcription === 'unavailable'
      ? 'Ce poste ne sait pas encore transcrire sur place : mettez le runner à jour.'
      : 'La transcription n\'a pas pu démarrer sur le poste ; le fichier, lui, est bien déposé.');
    this.phase.set('done');
  }

  /** Demande l'avancement au poste, encore et encore, jusqu'à la fin du travail. */
  private watch(uploadId: string): void {
    this.stopWatching();
    this.watching = setTimeout(() => {
      this.radar.recordingProgress(this.data.hostId, uploadId).subscribe({
        next: (progress) => this.applyProgress(progress, uploadId),
        error: () => this.missed(uploadId),
      });
    }, RadarDepositDialogComponent.POLL_MS);
  }

  private applyProgress(progress: RadarRecordingProgress, uploadId: string): void {
    this.misses = 0;
    if (!progress.known) {
      // Le runner a redémarré : le travail n'est plus suivi, le fichier déposé reste bon.
      this.outcome.set('Le poste ne suit plus cette transcription (runner redémarré) ; le fichier est déposé.');
      this.phase.set('done');
      return;
    }
    if (progress.phaseLabel) {
      this.phaseLabel.set(progress.phaseLabel);
    }
    if (!progress.over) {
      this.watch(uploadId);
      return;
    }
    this.outcome.set(progress.failure
      ? `La transcription n'a pas abouti : ${progress.failure}`
      : 'Transcription terminée sur la machine ; seul le texte en est sorti.');
    this.phase.set('done');
  }

  private missed(uploadId: string): void {
    this.misses += 1;
    if (this.misses < RadarDepositDialogComponent.MAX_MISSES) {
      this.watch(uploadId);
      return;
    }
    this.outcome.set('Le poste ne répond plus : la transcription se poursuit sur la machine, '
      + 'son résultat apparaîtra dans les réunions.');
    this.phase.set('done');
  }

  private stopWatching(): void {
    if (this.watching !== null) {
      clearTimeout(this.watching);
      this.watching = null;
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
