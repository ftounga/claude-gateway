import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

/** Contexte du dialogue : le dépôt visé et sa branche de base, qui est interdite à la publication. */
export interface GitPushDialogData {
  gitRepo: string | null;
  baseBranch: string | null;
  suggestedBranch: string;
}

/** Publication demandée par l'utilisateur. Champs vides ⇒ valeurs par défaut du backend. */
export interface PickedGitPush {
  branch?: string;
  message?: string;
}

/** Référence de branche acceptée — même alphabet que la validation du backend (SF-31-04). */
const BRANCH_PATTERN = /^[A-Za-z0-9._/-]{1,255}$/;

/**
 * Dialogue de publication du travail sur une branche dédiée (F-31 / SF-31-04).
 *
 * <p>La branche de base est **refusée ici comme côté serveur** : le travail de Claude arrive toujours
 * sur une branche que l'on relit avant de fusionner. Le dire pendant la saisie évite de découvrir le
 * refus après avoir consommé un tour d'exécution.</p>
 */
@Component({
  selector: 'app-git-push-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './git-push-dialog.component.html',
  styleUrl: './git-push-dialog.component.scss',
})
export class GitPushDialogComponent {
  private readonly dialogRef = inject<MatDialogRef<GitPushDialogComponent, PickedGitPush>>(MatDialogRef);
  readonly data = inject<GitPushDialogData>(MAT_DIALOG_DATA);

  readonly branch = signal(this.data.suggestedBranch);
  readonly message = signal('');

  /** Message d'erreur sous le champ branche, ou `null` s'il n'y a rien à signaler. */
  readonly branchError = computed(() => {
    const value = this.branch().trim();
    if (value.length === 0) {
      return 'Indiquez une branche.';
    }
    if (!BRANCH_PATTERN.test(value) || value.includes('..') || value.startsWith('-')) {
      return 'Nom de branche invalide.';
    }
    if (value === this.data.baseBranch) {
      return `Le travail se publie sur une branche dédiée, jamais sur « ${this.data.baseBranch} ».`;
    }
    return null;
  });

  readonly valid = computed(() => this.branchError() === null);

  cancel(): void {
    this.dialogRef.close();
  }

  confirm(): void {
    if (!this.valid()) {
      return;
    }
    const message = this.message().trim();
    this.dialogRef.close({
      branch: this.branch().trim(),
      message: message.length > 0 ? message : undefined,
    });
  }
}
