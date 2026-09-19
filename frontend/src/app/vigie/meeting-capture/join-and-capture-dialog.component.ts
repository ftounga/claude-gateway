import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { CreateMeetingRequest } from '../../core/models/teams-meeting.models';

/** Donnée d'entrée du dialogue : le nom du poste, pour le titre. */
export interface JoinAndCaptureDialogData {
  hostName: string;
}

/**
 * Dialogue « Rejoindre & capturer » (F-128 / SF-128-01, §2bis). Recueille l'URL de la réunion, un
 * titre facultatif, la durée de rétention et le <b>consentement</b> (obligatoire), puis rend une
 * {@link CreateMeetingRequest}. Aucune capture silencieuse : sans consentement, on ne peut pas valider.
 */
@Component({
  selector: 'app-join-and-capture-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatButtonModule,
  ],
  template: `
    <h2 mat-dialog-title>Rejoindre la réunion</h2>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <mat-dialog-content class="join-capture">
        <p class="join-capture__lede">
          La réunion s'ouvre dans le <strong>Chrome managé</strong> de {{ data.hostName }} et vous y entrez
          réellement. Une fois « En réunion », vous démarrerez l'enregistrement. Rejoignez depuis la Vigie,
          pas dans un navigateur à côté.
        </p>

        <mat-form-field appearance="outline" class="join-capture__field">
          <mat-label>Lien de la réunion Teams</mat-label>
          <input matInput formControlName="meetingUrl" placeholder="https://teams.microsoft.com/l/meetup-join/…" />
          @if (form.controls.meetingUrl.hasError('required')) {
            <mat-error>Le lien de la réunion est obligatoire.</mat-error>
          }
          @if (form.controls.meetingUrl.hasError('pattern')) {
            <mat-error>Entrez une adresse web valide (http(s)://…).</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" class="join-capture__field">
          <mat-label>Titre (facultatif)</mat-label>
          <input matInput formControlName="title" maxlength="300" />
        </mat-form-field>

        <mat-form-field appearance="outline" class="join-capture__field">
          <mat-label>Conservation (jours)</mat-label>
          <input matInput type="number" formControlName="retentionDays" min="1" max="365" />
          @if (form.controls.retentionDays.invalid) {
            <mat-error>Entre 1 et 365 jours.</mat-error>
          }
        </mat-form-field>

        <mat-checkbox formControlName="consent" class="join-capture__consent">
          J'ai prévenu les participants que j'enregistre cette réunion.
        </mat-checkbox>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [mat-dialog-close]="null">Annuler</button>
        <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid">
          Rejoindre
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: [
    `
      .join-capture {
        display: flex;
        flex-direction: column;
        gap: var(--cg-space-3, 16px);
        min-width: 320px;
      }
      .join-capture__lede {
        margin: 0 0 var(--cg-space-1, 4px);
        color: var(--cg-text-secondary, #6b7a8d);
        font-size: 13px;
      }
      .join-capture__field {
        width: 100%;
      }
      .join-capture__consent {
        margin-top: var(--cg-space-1, 4px);
      }
    `,
  ],
})
export class JoinAndCaptureDialogComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<JoinAndCaptureDialogComponent>);
  readonly data = inject<JoinAndCaptureDialogData>(MAT_DIALOG_DATA);

  readonly form = this.fb.nonNullable.group({
    meetingUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/i)]],
    title: [''],
    retentionDays: [30, [Validators.required, Validators.min(1), Validators.max(365)]],
    consent: [false, [Validators.requiredTrue]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: CreateMeetingRequest = {
      meetingUrl: value.meetingUrl.trim(),
      title: value.title.trim() ? value.title.trim() : null,
      consentAcknowledged: value.consent,
      retentionDays: value.retentionDays,
    };
    this.dialogRef.close(request);
  }
}
