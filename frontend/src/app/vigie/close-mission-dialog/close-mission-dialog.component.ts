import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { RadarExportOfferComponent } from '../radar-export/radar-export-offer.component';
import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';

export interface CloseMissionDialogData {
  hostId: string;
  hostName: string;
}

export interface CloseMissionDialogResult {
  confirmed: boolean;
  /** Effacer aussi son Radar après la clôture — irréversible, jamais présumé. */
  purgeRadar: boolean;
  /** Effacer aussi ses pages après la clôture (F-109 / SF-109-04) — irréversible, jamais présumé. */
  purgePages?: boolean;
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
      <!-- SES PAGES (F-109 / SF-109-04) : proposées au téléchargement, effacées seulement si c'est coché. -->
      <div class="close-mission__pages">
        <p class="close-mission__pages-lead">Ses pages peuvent être gardées : téléchargez-les (ZIP).</p>
        <button mat-stroked-button type="button" class="close-mission__pages-export" [disabled]="pagesState() === 'busy'"
          (click)="exportPages()">
          <mat-icon>download</mat-icon>
          {{ pagesState() === 'error' ? 'Réessayer' : 'Télécharger ses pages' }}
        </button>
        @if (pagesState() === 'done') {
          <span class="close-mission__pages-done" role="status">Pages téléchargées.</span>
        }
        @if (pagesState() === 'error') {
          <span class="close-mission__pages-error" role="alert">Les pages n'ont pas pu être téléchargées.</span>
        }
      </div>
      <mat-checkbox
        class="close-mission__purge-pages"
        [checked]="purgePages()"
        (change)="purgePages.set($event.checked)"
      >
        Supprimer aussi ses pages et leurs versions. Irréversible.
      </mat-checkbox>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button type="button" class="close-mission__cancel" (click)="cancel()">Annuler</button>
      <button
        mat-flat-button
        type="button"
        class="close-mission__confirm"
        [color]="purgeRadar() || purgePages() ? 'warn' : 'primary'"
        (click)="confirm()"
      >
        {{ confirmLabel() }}
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

    .close-mission__purge,
    .close-mission__purge-pages {
      display: block;
      margin-top: var(--cg-space-3);
    }

    .close-mission__pages {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-3);
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
    }

    .close-mission__pages-lead {
      flex-basis: 100%;
      margin: 0;
      font-size: 14px;
    }

    .close-mission__pages-error {
      color: var(--cg-error);
    }
  `,
})
export class CloseMissionDialogComponent {
  readonly data = inject<CloseMissionDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CloseMissionDialogComponent, CloseMissionDialogResult>);

  private readonly pages = inject(PagesService);
  private readonly files = inject(ExportService);

  readonly purgeRadar = signal(false);
  readonly purgePages = signal(false);
  readonly pagesState = signal<'idle' | 'busy' | 'done' | 'error'>('idle');

  confirmLabel(): string {
    if (this.purgeRadar() && this.purgePages()) {
      return 'Clôturer et effacer Radar et pages';
    }
    if (this.purgeRadar()) {
      return 'Clôturer et effacer le Radar';
    }
    return this.purgePages() ? 'Clôturer et effacer les pages' : 'Clôturer la mission';
  }

  /** Télécharge l'archive des pages du client ; rien n'est effacé. */
  exportPages(): void {
    this.pagesState.set('busy');
    this.pages.exportPlace(this.data.hostId, 'VIGIE').subscribe({
      next: (response) => {
        this.files.triggerDownload(response, 'pages.zip');
        this.pagesState.set('done');
      },
      error: () => this.pagesState.set('error'),
    });
  }

  cancel(): void {
    this.dialogRef.close({ confirmed: false, purgeRadar: false, purgePages: false });
  }

  confirm(): void {
    this.dialogRef.close({ confirmed: true, purgeRadar: this.purgeRadar(), purgePages: this.purgePages() });
  }
}
