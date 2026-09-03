import { DatePipe } from '@angular/common';
import {
  AfterViewInit,
  Component,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { DocumentsService } from '../core/services/documents.service';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import {
  DocumentDetailResponse,
  DocumentResponse,
  DocumentStatus,
} from '../core/models/documents.models';

/** Métadonnées d'affichage d'un statut (libellé FR + classe de badge du design system). */
interface StatusDisplay {
  label: string;
  badgeClass: string;
}

const STATUS_DISPLAY: Record<DocumentStatus, StatusDisplay> = {
  UPLOADED: { label: 'Reçu', badgeClass: 'badge--neutral' },
  PROCESSING: { label: 'En cours', badgeClass: 'badge--warning' },
  EXTRACTED: { label: 'Extrait', badgeClass: 'badge--success' },
  INDEXING: { label: 'Indexation…', badgeClass: 'badge--warning' },
  INDEXED: { label: 'Indexé', badgeClass: 'badge--success' },
  FAILED: { label: 'Échec', badgeClass: 'badge--error' },
};

/** États « en cours » pour lesquels un rafraîchissement périodique est utile. */
const IN_PROGRESS_STATUSES: readonly DocumentStatus[] = ['PROCESSING', 'INDEXING'];

/**
 * Écran documents F-05 : soumission d'un document à l'OCR, suivi des statuts et consultation du
 * texte extrait. Ne parle qu'à Claude Gateway (`/api/documents`) ; l'isolation est garantie côté
 * backend via le JWT. Rafraîchissement périodique léger tant qu'un document est `PROCESSING`.
 */
@Component({
  selector: 'app-documents',
  imports: [
    DatePipe,
    RouterLink,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './documents.component.html',
  styleUrl: './documents.component.scss',
})
export class DocumentsComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly documentsService = inject(DocumentsService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly displayedColumns = ['filename', 'mediaType', 'status', 'chunks', 'createdAt', 'actions'];
  readonly dataSource = new MatTableDataSource<DocumentResponse>([]);
  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly selected = signal<DocumentDetailResponse | null>(null);
  readonly selectedFile = signal<File | null>(null);

  @ViewChild(MatPaginator) paginator?: MatPaginator;

  /** Intervalle de rafraîchissement actif tant qu'un document est en cours de traitement. */
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.refresh();
  }

  ngAfterViewInit(): void {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
    }
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  /**
   * Choisir un fichier **lance l'extraction** (F-08 / SF-08-03).
   *
   * <p>L'écran demandait auparavant un second clic sur « Lancer l'OCR ». Un utilisateur qui
   * découvrait l'outil choisissait son fichier et attendait : rien ne partait, et comme rien
   * n'était envoyé, aucune erreur ne s'affichait non plus. Un choix de fichier <b>est</b> une
   * demande d'extraction — il n'y a pas d'autre geste à attendre.</p>
   */
  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files && input.files.length > 0 ? input.files[0] : null;
    this.selectedFile.set(file);
    // Le champ est vidé pour que re-choisir le même fichier redéclenche bien un `change`.
    input.value = '';
    if (file) {
      this.submit();
    }
  }

  submit(): void {
    const file = this.selectedFile();
    if (!file || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.documentsService.submit(file).subscribe({
      next: () => {
        this.submitting.set(false);
        this.selectedFile.set(null);
        this.notify('Document soumis à l’extraction OCR.', 'snack-success');
        this.refresh();
      },
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        this.notify(this.submitErrorMessage(error), 'snack-error');
      },
    });
  }

  refresh(): void {
    this.loading.set(true);
    this.documentsService.list().subscribe({
      next: (documents) => {
        this.dataSource.data = documents;
        this.loading.set(false);
        this.syncPolling(documents);
      },
      error: () => {
        this.loading.set(false);
        this.notify('Impossible de charger vos documents.', 'snack-error');
      },
    });
  }

  view(document: DocumentResponse): void {
    this.documentsService.get(document.id).subscribe({
      next: (detail) => this.selected.set(detail),
      error: () => this.notify('Impossible de charger le document.', 'snack-error'),
    });
  }

  /**
   * Suppression définitive d'un document (droit à l'effacement RGPD, F-08). Ouvre une confirmation
   * destructive (`MatDialog`) ; à confirmation seulement, appelle `DELETE /api/documents/{id}` puis
   * rafraîchit la liste. Le backend supprime en cascade les chunks/vecteurs dérivés.
   */
  remove(document: DocumentResponse): void {
    const data: ConfirmDialogData = {
      title: 'Supprimer le document',
      message: `Supprimer définitivement « ${document.filename} » ? Cette action est irréversible et efface aussi les données dérivées (texte extrait, index).`,
      confirmLabel: 'Supprimer',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.performDelete(document);
        }
      });
  }

  private performDelete(document: DocumentResponse): void {
    this.documentsService.delete(document.id).subscribe({
      next: () => {
        if (this.selected()?.id === document.id) {
          this.selected.set(null);
        }
        this.notify('Document supprimé.', 'snack-success');
        this.refresh();
      },
      error: () => this.notify('Impossible de supprimer le document.', 'snack-error'),
    });
  }

  statusDisplay(status: DocumentStatus): StatusDisplay {
    return STATUS_DISPLAY[status];
  }

  /** (Re)démarre ou arrête le rafraîchissement selon la présence de documents « en cours ». */
  private syncPolling(documents: DocumentResponse[]): void {
    const hasPending = documents.some((d) => IN_PROGRESS_STATUSES.includes(d.status));
    if (hasPending && !this.pollHandle) {
      this.pollHandle = setInterval(() => this.refresh(), 5000);
    } else if (!hasPending) {
      this.stopPolling();
    }
  }

  private stopPolling(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  /**
   * Message d'échec : dire **ce qui est accepté**, pas seulement que c'est refusé.
   *
   * <p>« Type non supporté » laissait l'utilisateur deviner lesquels le sont — et un PDF déclaré
   * {@code application/octet-stream} par le navigateur tombe ici alors que c'est bien un PDF.</p>
   */
  private submitErrorMessage(error: HttpErrorResponse): string {
    const detail = typeof error.error?.message === 'string' ? error.error.message : null;
    if (error.status === 415) {
      return detail ?? 'Format refusé. Formats acceptés : PDF, PNG, JPEG, TIFF.';
    }
    if (error.status === 413) {
      return detail ?? 'Document trop volumineux (20 Mo au maximum).';
    }
    if (error.status === 401) {
      return 'Votre session a expiré : reconnectez-vous puis réessayez.';
    }
    return detail ?? 'Impossible de soumettre le document.';
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000, panelClass: [panelClass] });
  }
}
