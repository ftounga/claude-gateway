import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { PageVersionSummary } from '../../core/models/pages.models';
import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';

export interface PageVersionsDialogData {
  pageId: string;
  title: string;
}

/** La taille lisible d'une version : « 12 Ko », « 1,4 Mo ». */
export function sizeLabel(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`;
}

/**
 * **Les versions précédentes d'une page** (F-109 / SF-109-04) : chacune se *voit* (plein écran, nouvel onglet) et
 * se *télécharge*. Les dix dernières sont conservées.
 */
@Component({
  selector: 'app-page-versions-dialog',
  imports: [DatePipe, MatButtonModule, MatDialogModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>Versions de « {{ data.title }} »</h2>
    <mat-dialog-content>
      @if (versions(); as list) {
        <ul class="page-versions">
          @for (version of list; track version.version) {
            <li class="page-versions__item">
              <span class="page-versions__label">
                <strong>Version {{ version.version }}</strong>
                @if ($first) {
                  <span class="badge badge--info">courante</span>
                }
                <span class="page-versions__meta">{{ version.createdAt | date: 'dd/MM/yyyy HH:mm' }} · {{ size(version.sizeBytes) }}</span>
              </span>
              <span class="page-versions__actions">
                <button mat-stroked-button type="button" class="page-versions__view" (click)="view(version)">
                  <mat-icon>open_in_new</mat-icon>
                  Voir
                </button>
                <button mat-stroked-button type="button" class="page-versions__download" (click)="download(version)">
                  <mat-icon>download</mat-icon>
                  Télécharger
                </button>
              </span>
            </li>
          }
        </ul>
        <p class="page-versions__note">Les dix dernières versions sont conservées.</p>
      } @else if (failed()) {
        <p class="page-versions__error" role="alert">Les versions n'ont pas pu être lues.</p>
      } @else {
        <mat-spinner diameter="28"></mat-spinner>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button mat-dialog-close type="button">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .page-versions {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .page-versions__item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) 0;
      border-bottom: 1px solid var(--cg-divider);
    }

    .page-versions__label {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .page-versions__meta,
    .page-versions__note {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .page-versions__actions {
      display: flex;
      gap: var(--cg-space-2);
    }

    .page-versions__error {
      color: var(--cg-error);
    }
  `,
})
export class PageVersionsDialogComponent {
  readonly data = inject<PageVersionsDialogData>(MAT_DIALOG_DATA);
  private readonly pages = inject(PagesService);
  private readonly files = inject(ExportService);
  private readonly snackBar = inject(MatSnackBar);

  readonly versions = signal<PageVersionSummary[] | null>(null);
  readonly failed = signal(false);

  constructor() {
    this.pages.versions(this.data.pageId).subscribe({
      next: (list) => this.versions.set(list),
      error: () => this.failed.set(true),
    });
  }

  size(bytes: number): string {
    return sizeLabel(bytes);
  }

  view(version: PageVersionSummary): void {
    window.open(`/pages/${encodeURIComponent(this.data.pageId)}?version=${version.version}`, '_blank', 'noopener');
  }

  download(version: PageVersionSummary): void {
    this.pages.download(this.data.pageId, version.version).subscribe({
      next: (response) => this.files.triggerDownload(response, `page-v${version.version}.html`),
      error: () => this.snackBar.open("La version n'a pas pu être téléchargée.", 'Fermer',
        { duration: 8000, panelClass: 'snack-error' }),
    });
  }
}
