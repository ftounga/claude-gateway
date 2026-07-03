import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DocumentsService } from '../../core/services/documents.service';
import { DocumentResponse, DocumentStatus } from '../../core/models/documents.models';

/** Document de la bibliothèque retenu à l'import (F-24). */
export interface PickedLibraryDocument {
  id: string;
  filename: string;
}

/** Statuts pour lesquels le texte OCR est disponible et exploitable comme contexte (F-24). */
const USABLE_STATUSES: ReadonlySet<DocumentStatus> = new Set<DocumentStatus>([
  'EXTRACTED',
  'INDEXING',
  'INDEXED',
]);

/**
 * Dialogue de sélection de documents de la bibliothèque personnelle (F-08) à importer comme
 * contexte dans une conversation (F-24 / SF-24-02). Ne liste que les documents dont le texte est
 * déjà extrait (statut exploitable). Sélection multiple ; renvoie les documents choisis.
 *
 * Le frontend ne parle qu'à `/api` via {@link DocumentsService} ; l'isolation `user_id` est garantie
 * côté backend. Le contexte réel est injecté par SF-24-01 à l'envoi.
 */
@Component({
  selector: 'app-library-picker-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatListModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './library-picker-dialog.component.html',
  styleUrl: './library-picker-dialog.component.scss',
})
export class LibraryPickerDialogComponent {
  private readonly documentsService = inject(DocumentsService);
  private readonly dialogRef =
    inject<MatDialogRef<LibraryPickerDialogComponent, PickedLibraryDocument[]>>(MatDialogRef);

  readonly loading = signal(true);
  readonly error = signal(false);
  /** Documents exploitables (texte extrait), les plus récents d'abord. */
  readonly documents = signal<DocumentResponse[]>([]);
  /** Ids sélectionnés. */
  readonly selected = signal<Set<string>>(new Set());

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.documentsService.list().subscribe({
      next: (docs) => {
        this.documents.set(docs.filter((d) => USABLE_STATUSES.has(d.status)));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  isSelected(id: string): boolean {
    return this.selected().has(id);
  }

  /** Bascule la sélection d'un document. */
  toggle(id: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  /** Valide : renvoie les documents sélectionnés (id + nom) au composer. */
  confirm(): void {
    const chosen = this.documents()
      .filter((d) => this.selected().has(d.id))
      .map((d) => ({ id: d.id, filename: d.filename }));
    this.dialogRef.close(chosen);
  }

  /** Taille lisible (o / Ko / Mo), sans dépendance de formatage. */
  formatFileSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} o`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} Ko`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} Mo`;
  }
}
