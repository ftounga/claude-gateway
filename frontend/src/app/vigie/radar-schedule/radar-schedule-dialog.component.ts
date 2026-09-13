import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarSchedule } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { dayLabel } from '../radar-subject/radar-subject-view';
import {
  DEFAULT_SYNC_TIME,
  browserTimeZone,
  canSave,
  needsAuthorization,
  scheduleErrorOf,
  scheduleRequest,
  zoneFor,
} from './radar-schedule';

export interface RadarScheduleDialogData {
  hostId: string;
  hostName: string;
  schedule: RadarSchedule | null;
}

/**
 * **Régler la synchro du soir** d'un client (F-100 / SF-100-07) : l'activer ou non, choisir l'heure. La
 * première activation demande à l'utilisateur de confirmer que son client autorise la conservation
 * d'extraits (cadrage §14) — la gateway l'exige aussi. Régler ne lance aucune synchro.
 */
@Component({
  selector: 'app-radar-schedule-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>Synchro du soir de « {{ data.hostName }} »</h2>
    <mat-dialog-content class="schedule">
      <p class="schedule__lead">
        Chaque soir, le runner lit ce qui a bougé dans Teams depuis la dernière synchro ; le Radar est à jour le
        matin. Portable fermé à l'heure prévue : la synchro part à la prochaine connexion.
      </p>

      <mat-checkbox class="schedule__enabled" [checked]="enabled()" (change)="enabled.set($event.checked)">
        Synchroniser chaque soir
      </mat-checkbox>

      <div class="schedule__time-row">
        <mat-form-field appearance="outline" class="schedule__time" subscriptSizing="dynamic">
          <mat-label>Heure</mat-label>
          <input matInput type="time" class="schedule__time-input" [value]="syncTime()"
            (input)="syncTime.set($any($event.target).value)" required />
        </mat-form-field>
        <span class="schedule__zone">heure de {{ zone }}</span>
      </div>

      @if (authorizationRequired()) {
        <mat-checkbox class="schedule__authorization" [checked]="authorized()" (change)="authorized.set($event.checked)">
          Mon client autorise la conservation d'extraits de ses échanges dans l'application : citations courtes et
          liens, jamais d'archives.
        </mat-checkbox>
      } @else if (authorizedOn()) {
        <p class="schedule__authorized">Autorisation du client confirmée le {{ authorizedOn() }}.</p>
      }

      <!-- LE RÉSUMÉ DU MATIN PAR COURRIEL (F-110 / SF-110-04) : une option par client. -->
      <mat-checkbox class="schedule__morning-email" [checked]="morningEmail()" (change)="morningEmail.set($event.checked)">
        Recevoir le résumé du matin par courriel
      </mat-checkbox>
      <p class="schedule__note schedule__morning-email-note">
        Envoyé après la synchro du soir, à l'adresse de réception du client (réglée dans son en-tête), sinon à
        l'adresse de votre compte.
      </p>

      <p class="schedule__note">Régler l'heure ne lance pas de synchro : la première partira au prochain créneau.</p>

      @if (error(); as message) {
        <p class="schedule__error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" class="schedule__cancel" [disabled]="saving()" (click)="cancel()">Annuler</button>
      <button mat-flat-button color="primary" type="button" class="schedule__save" [disabled]="!ready() || saving()"
        (click)="save()">
        @if (saving()) {
          <mat-spinner diameter="16"></mat-spinner>
        }
        Enregistrer
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .schedule__lead {
      margin: 0 0 var(--cg-space-3);
      color: var(--cg-text-secondary);
    }

    .schedule__enabled,
    .schedule__authorization,
    .schedule__morning-email {
      display: block;
    }

    .schedule__morning-email {
      margin-top: var(--cg-space-3);
    }

    .schedule__time-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin: var(--cg-space-3) 0;
    }

    .schedule__time {
      width: 160px;
    }

    .schedule__zone,
    .schedule__authorized,
    .schedule__note {
      color: var(--cg-text-secondary);
      font-size: 14px;
    }

    .schedule__authorized,
    .schedule__note {
      margin: var(--cg-space-2) 0 0;
    }

    .schedule__error {
      margin: var(--cg-space-2) 0 0;
      color: var(--cg-error);
    }
  `,
})
export class RadarScheduleDialogComponent {
  readonly data = inject<RadarScheduleDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<RadarScheduleDialogComponent, RadarSchedule>);
  private readonly radar = inject(RadarService);

  static readonly DIALOG_WIDTH = '560px';

  private readonly browserZone = browserTimeZone();
  readonly zone = zoneFor(this.data.schedule, this.browserZone);

  /** À l'ouverture sur un Radar jamais activé, la case est cochée : c'est ce qu'on vient faire. */
  readonly enabled = signal(this.data.schedule?.clientAuthorizedAt ? this.data.schedule.enabled : true);
  readonly syncTime = signal(this.data.schedule?.syncTime || DEFAULT_SYNC_TIME);
  readonly authorized = signal(false);
  /** Le résumé du matin par courriel (F-110 / SF-110-04). */
  readonly morningEmail = signal(this.data.schedule?.morningEmail ?? false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  readonly authorizationRequired = computed(() => needsAuthorization(this.data.schedule, this.enabled()));
  readonly authorizedOn = computed(() => dayLabel(this.data.schedule?.clientAuthorizedAt ?? null));
  readonly ready = computed(() =>
    canSave(this.data.schedule, this.enabled(), this.syncTime(), this.authorized()));

  cancel(): void {
    this.dialogRef.close();
  }

  save(): void {
    if (!this.ready() || this.saving()) {
      return;
    }
    const request = scheduleRequest(this.data.schedule, this.enabled(), this.syncTime(),
      this.authorizationRequired() && this.authorized(), this.browserZone, this.morningEmail());
    this.saving.set(true);
    this.error.set(null);
    this.radar.updateSchedule(this.data.hostId, request).subscribe({
      next: (schedule) => {
        this.saving.set(false);
        this.dialogRef.close(schedule);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(scheduleErrorOf(err));
      },
    });
  }
}
