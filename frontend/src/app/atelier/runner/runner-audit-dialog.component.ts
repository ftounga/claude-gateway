import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RunnerAuditEntry } from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';

/** Données d'ouverture du journal : le projet dont on veut l'activité. */
export interface RunnerAuditDialogData {
  workspaceId: string;
  workspaceName: string;
}

/** Nombre de lignes demandées au backend (borné côté serveur à 200). */
export const RUNNER_AUDIT_LIMIT = 100;

/**
 * Journal d'activité du runner (F-38 / SF-38-08, décision D11) : ce qui a été **lu, écrit et
 * exécuté** sur la machine, du plus récent au plus ancien.
 *
 * <p>Le journal ne montre ni contenu de fichier ni sortie de commande — seulement l'action, sa
 * cible et son issue. Dire précisément ce qui a été fait est le but ; rejouer ce qui a été lu en
 * serait un autre, et il exposerait à nouveau ce que les exclusions du runner ont écarté.</p>
 */
@Component({
  selector: 'app-runner-audit-dialog',
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './runner-audit-dialog.component.html',
  styleUrl: './runner-audit-dialog.component.scss',
})
export class RunnerAuditDialogComponent {
  private readonly atelier = inject(AtelierService);
  private readonly dialogRef = inject<MatDialogRef<RunnerAuditDialogComponent>>(MatDialogRef);
  readonly data = inject<RunnerAuditDialogData>(MAT_DIALOG_DATA);

  readonly entries = signal<RunnerAuditEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    this.refresh();
  }

  /** Relit le journal. Un échec est **dit** : un journal vide et un journal illisible diffèrent. */
  refresh(): void {
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.atelier.getRunnerAudit(this.data.workspaceId, RUNNER_AUDIT_LIMIT).subscribe({
      next: (entries) => {
        this.loading.set(false);
        this.entries.set(entries);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.entries.set([]);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 404
            ? 'Projet introuvable.'
            : "Le journal n'a pas pu être relu. Veuillez réessayer.",
        );
      },
    });
  }

  /** Libellé lisible d'une action. Un outil inconnu est affiché tel quel, jamais masqué. */
  toolLabel(entry: RunnerAuditEntry): string {
    switch (entry.tool) {
      case 'read_file':
        return 'Lecture';
      case 'write_file':
        return 'Écriture';
      case 'list_files':
        return 'Listage';
      case 'search_files':
        return 'Recherche';
      case 'bash':
        return 'Commande';
      case 'bootstrap':
        return 'Consigne système';
      case 'kill_switch':
        return 'Coupe-circuit';
      default:
        return entry.tool;
    }
  }

  /** Icône de l'action, alignée sur les étapes affichées dans le fil. */
  toolIcon(entry: RunnerAuditEntry): string {
    switch (entry.tool) {
      case 'read_file':
        return 'visibility';
      case 'write_file':
        return 'edit';
      case 'list_files':
        return 'folder_open';
      case 'search_files':
        return 'search';
      case 'bash':
        return 'terminal';
      case 'kill_switch':
        return 'power_settings_new';
      default:
        return 'description';
    }
  }

  /** Libellé d'issue en français : le journal se lit sans connaître le protocole. */
  outcomeLabel(entry: RunnerAuditEntry): string {
    switch (entry.outcome) {
      case 'OK':
        return 'Effectué';
      case 'DENIED':
        return 'Refusé';
      case 'TIMEOUT':
        return 'Délai dépassé';
      case 'CANCELLED':
        return 'Interrompu';
      default:
        return 'Échec';
    }
  }

  /** Vrai si l'issue doit être signalée visuellement (tout ce qui n'a pas abouti). */
  isFailure(entry: RunnerAuditEntry): boolean {
    return entry.outcome !== 'OK';
  }

  /** Horodatage court, dans le fuseau du navigateur. */
  timeLabel(entry: RunnerAuditEntry): string {
    const date = new Date(entry.createdAt);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString();
  }

  /** Détail de droite : code de sortie, code d'erreur ou durée — jamais un contenu. */
  detailLabel(entry: RunnerAuditEntry): string {
    if (entry.errorCode) {
      return entry.errorCode;
    }
    if (entry.exitCode !== null && entry.exitCode !== undefined) {
      return `code ${entry.exitCode}`;
    }
    return entry.durationMs ? `${entry.durationMs} ms` : '';
  }

  close(): void {
    this.dialogRef.close();
  }
}
