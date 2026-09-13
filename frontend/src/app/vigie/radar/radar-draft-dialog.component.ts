import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { RadarDraftKind } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { draftErrorOf } from './radar-draft-view';

/** Ce que le dialogue reçoit. */
export interface RadarDraftDialogData {
  hostId: string;
  commitmentId: string;
  kind: RadarDraftKind;
  /** Le titre de l'engagement, tel que la colonne l'écrit. */
  title: string;
}

/**
 * **Une relance ou une présentation préparée** (F-104 / SF-104-05) : le brouillon, modifiable, *Copier* et *Ouvrir
 * la conversation*. **Rien n'est envoyé** : le Radar ne parle jamais à la place de l'utilisateur (cadrage §4.5).
 */
@Component({
  selector: 'app-radar-draft-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>{{ data.kind === 'FOLLOW_UP' ? 'Relance préparée' : 'Présentation préparée' }}</h2>
    <mat-dialog-content class="radar-draft">
      <p class="radar-draft__about">{{ data.title }}</p>
      @if (preparing()) {
        <div class="radar-draft__loading">
          <mat-spinner diameter="28"></mat-spinner>
          <span>Le Radar rédige un brouillon dans le ton du fil…</span>
        </div>
      } @else if (error()) {
        <p class="radar-draft__error" role="alert">{{ error() }}</p>
      } @else {
        <mat-form-field appearance="outline" class="radar-draft__field">
          <mat-label>Brouillon</mat-label>
          <textarea matInput rows="7" name="draft" [ngModel]="text()" (ngModelChange)="text.set($event)"></textarea>
          <mat-hint>Rien n'est envoyé : c'est vous qui envoyez.</mat-hint>
        </mat-form-field>
        @if (!conversationUrl()) {
          <p class="radar-draft__no-link">Aucune conversation Teams d'origine : copiez le brouillon.</p>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [disabled]="preparing()" (click)="prepare()">
        <mat-icon>refresh</mat-icon>
        Préparer à nouveau
      </button>
      @if (conversationUrl(); as href) {
        <a mat-stroked-button class="radar-draft__open" [href]="href" target="_blank" rel="noopener noreferrer">
          <mat-icon>open_in_new</mat-icon>
          Ouvrir la conversation
        </a>
      }
      <button mat-flat-button color="primary" type="button" class="radar-draft__copy"
        [disabled]="preparing() || text().trim().length === 0" (click)="copy()">
        <mat-icon>content_copy</mat-icon>
        Copier
      </button>
      <button mat-button type="button" mat-dialog-close>Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .radar-draft {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      min-width: min(520px, 80vw);
    }

    .radar-draft__about {
      margin: 0;
      font-size: 14px;
      font-weight: 600;
      color: var(--cg-text-primary);
    }

    .radar-draft__loading {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-3) 0;
      font-size: 14px;
      color: var(--cg-text-secondary);
    }

    .radar-draft__error {
      margin: 0;
      font-size: 14px;
      color: var(--cg-error);
    }

    .radar-draft__no-link {
      margin: 0;
      font-size: 13px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class RadarDraftDialogComponent implements OnInit {
  private readonly radar = inject(RadarService);
  private readonly snackBar = inject(MatSnackBar);
  readonly data = inject<RadarDraftDialogData>(MAT_DIALOG_DATA);
  readonly dialogRef = inject(MatDialogRef<RadarDraftDialogComponent>);

  readonly preparing = signal(false);
  readonly error = signal<string | null>(null);
  readonly text = signal('');
  readonly conversationUrl = signal<string | null>(null);

  ngOnInit(): void {
    this.prepare();
  }

  /** Un appel au fournisseur, décompté : seulement à l'ouverture et sur *Préparer à nouveau*. */
  prepare(): void {
    if (this.preparing()) {
      return;
    }
    this.preparing.set(true);
    this.error.set(null);
    this.radar.prepareDraft(this.data.hostId, this.data.commitmentId).subscribe({
      next: (draft) => {
        this.preparing.set(false);
        this.text.set(draft.text);
        this.conversationUrl.set(draft.conversationUrl);
      },
      error: (err: unknown) => {
        this.preparing.set(false);
        this.error.set(draftErrorOf(err));
      },
    });
  }

  /** Copie le brouillon tel qu'il est dans le champ, retouches comprises. */
  copy(): void {
    const text = this.text().trim();
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (!text) {
      return;
    }
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible sur ce navigateur.', 'Fermer', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    clipboard.writeText(text).then(
      () => this.snackBar.open('Brouillon copié : collez-le dans la conversation.', 'Fermer',
        { duration: 4000, panelClass: 'snack-success' }),
      () => this.snackBar.open('Copie impossible.', 'Fermer', { duration: 4000, panelClass: 'snack-error' }),
    );
  }
}
