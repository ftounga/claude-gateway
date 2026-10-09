import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **Télécharger une page en PDF** (F-184 / SF-184-03) — partagé par le panneau d'aperçu, le plein écran
 * et l'onglet Pages. L'impression prend quelques secondes : l'état « en cours » est tenu **par page**,
 * pour désactiver le bouton et ne jamais relancer une impression déjà partie.
 */
@Injectable({ providedIn: 'root' })
export class PagePdfDownloadService {
  private readonly pages = inject(PagesService);
  private readonly files = inject(ExportService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly pending = signal<ReadonlySet<string>>(new Set());

  /** Vrai pendant l'impression de cette page. */
  isBusy(pageId: string): boolean {
    return this.pending().has(pageId);
  }

  /** Imprime et télécharge la version voulue (courante par défaut). Sans effet si déjà en cours. */
  download(pageId: string, version?: number | null): void {
    if (this.isBusy(pageId)) {
      return;
    }
    this.setBusy(pageId, true);
    this.snackBar.open('Préparation du PDF…', undefined, { duration: 3000, panelClass: 'snack-info' });
    this.pages.pdf(pageId, version).subscribe({
      next: (response) => {
        this.setBusy(pageId, false);
        this.files.triggerDownload(response, 'page.pdf');
        if ((response.headers.get('X-Cg-Missing-Resources') ?? '').trim()) {
          this.snackBar.open("PDF téléchargé. Certains éléments externes n'ont pas pu être inclus.", 'Fermer',
            { duration: 8000, panelClass: 'snack-info' });
        } else {
          this.snackBar.dismiss();
        }
      },
      error: (error: unknown) => {
        this.setBusy(pageId, false);
        this.snackBar.open(PagePdfDownloadService.messageFor(error), 'Fermer',
          { duration: 8000, panelClass: 'snack-error' });
      },
    });
  }

  /** Le message lisible d'un échec, selon ce qui a échoué. */
  static messageFor(error: unknown): string {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    switch (status) {
      case 503:
        return "Le PDF n'a pas pu être produit pour le moment. Réessayez dans un instant.";
      case 422:
        return "Cette page n'a pas pu être imprimée en PDF (trop lourde ou trop complexe).";
      case 404:
        return 'Page introuvable : elle a peut-être été supprimée.';
      default:
        return "Le PDF n'a pas pu être téléchargé.";
    }
  }

  private setBusy(pageId: string, busy: boolean): void {
    this.pending.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(pageId);
      } else {
        next.delete(pageId);
      }
      return next;
    });
  }
}
