import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { CraLine, CraRecap, PosteBillingService } from '../../core/services/poste-billing.service';
import { httpErrorMessage } from '../../shared/http-error.util';

/**
 * **Le CRA par message** (F-124 / SF-124-03) : un dialogue de la Forge où l'utilisateur écrit son
 * compte rendu d'activité en langage naturel (« Free 20j, KG 13j »). Le modèle extrait, la Gateway
 * rapproche/valide/persiste, et le dialogue **récapitule ce qui a été compris** — écrit, refusé, ou
 * nom non reconnu (demandé, jamais deviné).
 *
 * <p>Au niveau **Forge** et non d'un poste : un message cite plusieurs clients. Charte, aucune
 * couleur nouvelle — les statuts empruntent les pastilles §5.</p>
 */
@Component({
  selector: 'app-cra-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './cra-dialog.component.html',
  styleUrl: './cra-dialog.component.scss',
})
export class CraDialogComponent {
  static readonly DIALOG_WIDTH = '560px';

  private readonly billing = inject(PosteBillingService);
  private readonly dialogRef = inject(MatDialogRef<CraDialogComponent, boolean>);

  readonly message = signal('');
  readonly submitting = signal(false);
  readonly recap = signal<CraRecap | null>(null);
  readonly error = signal<string | null>(null);

  /** Vrai dès qu'un CRA a été écrit : la Forge devra relire le cumul à la fermeture. */
  private changed = false;

  /** Envoie le message pour interprétation. Rien n'est deviné : le récap dit ce qui a été compris. */
  submit(): void {
    const text = this.message().trim();
    if (text.length === 0 || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.billing.submitCra(text).subscribe({
      next: (recap) => {
        this.submitting.set(false);
        this.recap.set(recap);
        if (recap.written > 0) {
          this.changed = true;
        }
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.error.set(httpErrorMessage(err, "Le CRA n'a pas pu être interprété. Veuillez réessayer."));
      },
    });
  }

  /** La classe de pastille §5 pour le statut d'une ligne (aucune couleur nouvelle). */
  badgeClass(line: CraLine): string {
    switch (line.status) {
      case 'WRITTEN':
        return 'badge badge--success';
      case 'REJECTED':
        return 'badge badge--error';
      default:
        return 'badge badge--warning';
    }
  }

  /** Le libellé écrit du statut (jamais la couleur seule). */
  statusLabel(line: CraLine): string {
    switch (line.status) {
      case 'WRITTEN':
        return 'écrit';
      case 'REJECTED':
        return 'refusé';
      default:
        return 'à préciser';
    }
  }

  /** La ligne, en clair : « Free — 20 j en 2025-09 », ou le nom cité pour un inconnu. */
  lineLabel(line: CraLine): string {
    if (line.status === 'UNKNOWN_HOST') {
      return line.cited;
    }
    const name = line.hostName ?? line.cited;
    if (line.status === 'WRITTEN' && line.days != null && line.month) {
      const days = line.days === 1 ? '1 j' : `${line.days} j`;
      return `${name} — ${days} en ${line.month}`;
    }
    return name;
  }

  close(): void {
    this.dialogRef.close(this.changed);
  }
}
