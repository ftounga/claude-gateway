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
import { MatIconModule } from '@angular/material/icon';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { DocumentsService } from '../core/services/documents.service';
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

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files && input.files.length > 0 ? input.files[0] : null);
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

  private submitErrorMessage(error: HttpErrorResponse): string {
    if (error.status === 415) {
      return 'Type de document non supporté.';
    }
    if (error.status === 413) {
      return 'Document trop volumineux.';
    }
    return 'Impossible de soumettre le document.';
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000, panelClass: [panelClass] });
  }
}
