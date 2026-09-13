import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarCorrectionView, RadarSubjectDetail } from '../../core/models/radar-subject.models';
import { RadarSubjectService } from '../../core/services/radar-subject.service';
import { httpErrorMessage } from '../../shared/http-error.util';
import { canSplit } from './radar-subject-journal';
import { sourceView, whenLabel } from './radar-subject-view';

export interface SplitSubjectDialogData {
  hostId: string;
  subject: RadarSubjectDetail;
}

/** Le sujet séparé : la correction (annulable) et le nom du nouveau sujet. */
export interface SplitSubjectDialogResult {
  correction: RadarCorrectionView;
  name: string;
}

/** Longueur maximale d'un nom de sujet, celle de la gateway. */
export const SUBJECT_NAME_MAX = 200;

/**
 * **Séparer un sujet** (F-99 / SF-99-06) : « ce n'est pas le même sujet ». L'utilisateur nomme le nouveau
 * sujet et coche ce qui part — des preuves de la chronologie, et s'il le veut des engagements. Rien n'est
 * coché d'office ; au moins une preuve part et une reste (tout séparer revient à renommer).
 *
 * <p>Le dialogue appelle la gateway lui-même : un refus (400, 409) reste affiché ici, sans perdre le
 * choix fait.</p>
 */
@Component({
  selector: 'app-split-subject-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>Séparer « {{ data.subject.name }} »</h2>
    <mat-dialog-content class="split-subject">
      <p class="split-subject__lead">
        Ce qui ne parle pas du même sujet part dans un nouveau sujet, avec ses preuves. Le Radar retiendra que
        les deux sujets sont différents. Vous pourrez annuler depuis la chronologie.
      </p>
      <mat-form-field appearance="outline" class="split-subject__name">
        <mat-label>Nom du nouveau sujet</mat-label>
        <input
          matInput
          class="split-subject__name-input"
          [attr.maxlength]="nameMax"
          [value]="name()"
          (input)="name.set($any($event.target).value)"
          autocomplete="off"
          required
        />
        @if (nameMissing()) {
          <mat-hint class="split-subject__name-missing">Nommez le nouveau sujet.</mat-hint>
        }
      </mat-form-field>

      <h3 class="split-subject__h">Preuves qui partent</h3>
      <ul class="split-subject__list split-subject__evidence">
        @for (evidence of data.subject.chronology; track evidence.id) {
          <li>
            <mat-checkbox
              [checked]="evidenceIds().has(evidence.id)"
              (change)="toggleEvidence(evidence.id, $event.checked)"
            >
              <span class="split-subject__item">
                <mat-icon class="split-subject__icon" aria-hidden="true">{{ sourceView(evidence.source).icon }}</mat-icon>
                <span class="split-subject__meta">{{ sourceView(evidence.source).label }} · {{ whenLabel(evidence.occurredAt) }}</span>
                <span class="split-subject__quote">{{ evidence.quote }}</span>
              </span>
            </mat-checkbox>
          </li>
        }
      </ul>
      @if (allSelected()) {
        <p class="split-subject__warning" role="status">
          Séparer toutes les preuves revient à renommer le sujet : laissez-en au moins une.
        </p>
      }

      @if (data.subject.commitments.length > 0) {
        <h3 class="split-subject__h">Engagements qui partent (facultatif)</h3>
        <ul class="split-subject__list split-subject__commitments">
          @for (commitment of data.subject.commitments; track commitment.id) {
            <li>
              <mat-checkbox
                [checked]="commitmentIds().has(commitment.id)"
                (change)="toggleCommitment(commitment.id, $event.checked)"
              >
                {{ commitment.description }}
              </mat-checkbox>
            </li>
          }
        </ul>
      }

      @if (error(); as message) {
        <p class="split-subject__error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" class="split-subject__cancel" [disabled]="saving()" (click)="cancel()">Annuler</button>
      <button
        mat-flat-button
        color="primary"
        type="button"
        class="split-subject__confirm"
        [disabled]="!ready() || saving()"
        (click)="confirm()"
      >
        @if (saving()) {
          <mat-spinner diameter="16"></mat-spinner>
        }
        Séparer
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .split-subject__lead {
      margin: 0 0 var(--cg-space-3);
      color: var(--cg-text-secondary);
    }

    .split-subject__name {
      width: 100%;
    }

    .split-subject__h {
      margin: var(--cg-space-2) 0 var(--cg-space-1);
      font-size: 14px;
      font-weight: 600;
    }

    .split-subject__list {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .split-subject__item {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--cg-space-1) var(--cg-space-2);
    }

    .split-subject__icon {
      color: var(--cg-text-secondary);
      font-size: 16px;
      width: 16px;
      height: 16px;
      align-self: center;
    }

    .split-subject__meta {
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .split-subject__quote {
      flex-basis: 100%;
      font-size: 14px;
    }

    .split-subject__warning {
      margin: var(--cg-space-1) 0 0;
      padding-left: var(--cg-space-2);
      border-left: 4px solid #F9A825;
      font-size: 14px;
    }

    .split-subject__error {
      margin: var(--cg-space-2) 0 0;
      color: var(--cg-error);
    }
  `,
})
export class SplitSubjectDialogComponent {
  readonly data = inject<SplitSubjectDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<SplitSubjectDialogComponent, SplitSubjectDialogResult>);
  private readonly subjects = inject(RadarSubjectService);

  static readonly DIALOG_WIDTH = '640px';

  readonly nameMax = SUBJECT_NAME_MAX;
  readonly name = signal('');
  readonly evidenceIds = signal<ReadonlySet<string>>(new Set());
  readonly commitmentIds = signal<ReadonlySet<string>>(new Set());
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  /** Des preuves sont choisies mais le nouveau sujet n'a pas de nom : on le dit. */
  readonly nameMissing = computed(() => this.evidenceIds().size > 0 && this.name().trim().length === 0);

  readonly allSelected = computed(() => this.data.subject.chronology.length > 0
    && this.evidenceIds().size === this.data.subject.chronology.length);
  readonly ready = computed(() =>
    canSplit(this.evidenceIds().size, this.data.subject.chronology.length, this.name()));

  readonly sourceView = sourceView;
  readonly whenLabel = whenLabel;

  toggleEvidence(id: string, checked: boolean): void {
    this.evidenceIds.update((ids) => toggled(ids, id, checked));
  }

  toggleCommitment(id: string, checked: boolean): void {
    this.commitmentIds.update((ids) => toggled(ids, id, checked));
  }

  cancel(): void {
    this.dialogRef.close();
  }

  confirm(): void {
    if (!this.ready() || this.saving()) {
      return;
    }
    const name = this.name().trim();
    // L'ordre de la chronologie, pas celui des clics : le corps envoyé se relit sans surprise.
    const evidenceIds = this.data.subject.chronology.map((e) => e.id).filter((id) => this.evidenceIds().has(id));
    const commitmentIds = this.data.subject.commitments.map((c) => c.id).filter((id) => this.commitmentIds().has(id));
    this.saving.set(true);
    this.error.set(null);
    this.subjects.split(this.data.hostId, this.data.subject.id, { name, evidenceIds, commitmentIds }).subscribe({
      next: (correction) => {
        this.saving.set(false);
        this.dialogRef.close({ correction, name });
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(httpErrorMessage(err, "Le sujet n'a pas pu être séparé. Rien n'a changé."));
      },
    });
  }
}

function toggled(ids: ReadonlySet<string>, id: string, checked: boolean): ReadonlySet<string> {
  const next = new Set(ids);
  if (checked) {
    next.add(id);
  } else {
    next.delete(id);
  }
  return next;
}
