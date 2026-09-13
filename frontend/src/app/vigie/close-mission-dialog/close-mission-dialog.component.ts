import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { RadarExportOfferComponent } from '../radar-export/radar-export-offer.component';

export interface CloseMissionDialogData {
  hostId: string;
  hostName: string;
}

export interface CloseMissionDialogResult {
  confirmed: boolean;
  /** Effacer aussi son Radar après la clôture — irréversible, jamais présumé. */
  purgeRadar: boolean;
}

/**
 * **Clôturer la mission d'un client de la Vigie** (F-99 / SF-99-07) : la mission se range, rien n'est
 * coupé ; l'export du Radar est proposé, et son effacement est une case décochée par défaut.
 *
 * <p>Le dialogue ne fait aucun appel de clôture ni de purge : il rend la décision, l'écran appelant clôt,
 * puis purge si demandé (la gateway exige une mission close pour `MISSION_CLOSED`).</p>
 */
@Component({
  selector: 'app-close-mission-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatIconModule, RadarExportOfferComponent],
  template: `
    <h2 mat-dialog-title>Clôturer la mission de « {{ data.hostName }} » ?</h2>
    <mat-dialog-content>
      <p class="close-mission__safe">
        <mat-icon aria-hidden="true">inventory_2</mat-icon>
        <span>
          Le client est rangé dans « Missions clôturées ». <strong>Rien n'est coupé</strong> : sa machine, son
          appairage et ses projets restent ; vous pourrez rouvrir la mission.
        </span>
      </p>
      <app-radar-export-offer
        class="close-mission__export"
        [hostId]="data.hostId"
        [hostName]="data.hostName"
        lead="Son Radar peut être gardé : exportez-le en Markdown."
      ></app-radar-export-offer>
      <mat-checkbox
        class="close-mission__purge"
        [checked]="purgeRadar()"
        (change)="purgeRadar.set($event.checked)"
      >
        Effacer aussi son Radar : sujets, engagements et annuaire. Irréversible.
      </mat-checkbox>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button type="button" class="close-mission__cancel" (click)="cancel()">Annuler</button>
      <button
        mat-flat-button
        type="button"
        class="close-mission__confirm"
        [color]="purgeRadar() ? 'warn' : 'primary'"
        (click)="confirm()"
      >
        {{ purgeRadar() ? 'Clôturer et effacer le Radar' : 'Clôturer la mission' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .close-mission__safe {
      display: flex;
      align-items: flex-start;
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-3);
      padding: var(--cg-space-3);
      border-radius: 8px;
      background: var(--cg-bg);

      mat-icon {
        color: var(--cg-text-secondary);
        flex: none;
      }
    }

    .close-mission__export {
      display: block;
    }

    .close-mission__purge {
      display: block;
      margin-top: var(--cg-space-3);
    }
  `,
})
export class CloseMissionDialogComponent {
  readonly data = inject<CloseMissionDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CloseMissionDialogComponent, CloseMissionDialogResult>);

  readonly purgeRadar = signal(false);

  cancel(): void {
    this.dialogRef.close({ confirmed: false, purgeRadar: false });
  }

  confirm(): void {
    this.dialogRef.close({ confirmed: true, purgeRadar: this.purgeRadar() });
  }
}
