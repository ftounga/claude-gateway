import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AskService } from '../core/services/ask.service';
import { ExportService } from '../core/services/export.service';
import { AskResponse } from '../core/models/ask.models';
import { AnswerExportRequest, ExportFormat } from '../core/models/export.models';

/**
 * Écran Q&A documentaire (F-07 / SF-07-02) : l'utilisateur pose une question sur ses documents
 * indexés et reçoit la réponse de Claude avec ses citations. Ne parle qu'à Claude Gateway
 * (`/api/ask`) ; l'isolation est garantie côté backend via le JWT. Contrat importé de SF-07-01.
 */
@Component({
  selector: 'app-ask',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './ask.component.html',
  styleUrl: './ask.component.scss',
})
export class AskComponent {
  private readonly askService = inject(AskService);
  private readonly exportService = inject(ExportService);
  private readonly snackBar = inject(MatSnackBar);

  readonly question = signal('');
  readonly loading = signal(false);
  readonly answer = signal<AskResponse | null>(null);
  /** Question effectivement posée pour la réponse affichée (figée à la soumission, pour l'export). */
  readonly askedQuestion = signal('');

  /** Vrai si la question courante est vide (après trim) : bouton désactivé. */
  isBlank(): boolean {
    return this.question().trim().length === 0;
  }

  submit(): void {
    const question = this.question().trim();
    if (question.length === 0 || this.loading()) {
      return;
    }
    this.loading.set(true);
    this.answer.set(null);
    this.askService.ask({ question }).subscribe({
      next: (response) => {
        this.answer.set(response);
        this.askedQuestion.set(question);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.notify(this.errorMessage(error));
      },
    });
  }

  /** Exporte la réponse citée courante au format demandé et déclenche le téléchargement (F-14). */
  exportAnswer(format: ExportFormat): void {
    const result = this.answer();
    if (!result) {
      return;
    }
    const body: AnswerExportRequest = {
      question: this.askedQuestion(),
      answer: result.answer,
      model: result.model,
      grounded: result.grounded,
      citations: result.citations.map((citation) => ({
        documentId: citation.documentId,
        filename: citation.filename,
        page: citation.page,
        chunkIndex: citation.chunkIndex,
        snippet: citation.snippet,
      })),
    };
    this.exportService.exportAnswer(body, format).subscribe({
      next: (response) =>
        this.exportService.triggerDownload(response, `reponse.${format === 'pdf' ? 'pdf' : 'md'}`),
      error: () => this.notify('L’export a échoué. Veuillez réessayer.'),
    });
  }

  private errorMessage(error: HttpErrorResponse): string {
    if (error.status === 402) {
      return 'Quota atteint. Vérifiez votre abonnement.';
    }
    if (error.status === 503) {
      return 'Service momentanément indisponible. Réessayez plus tard.';
    }
    return 'Une erreur est survenue. Veuillez réessayer.';
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000, panelClass: ['snack-error'] });
  }
}
