import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Contexte du dialogue : la branche de départ et celles qui existent déjà. */
export interface GitBranchDialogData {
  fromBranch: string;
  existing: string[];
}

/** Référence de branche acceptée — même alphabet que la validation du backend. */
const BRANCH_PATTERN = /^(?!\/)(?!.*\.\.)(?!.*\/\/)[A-Za-z0-9._\-/]{1,255}$/;

/**
 * Création d'une branche depuis l'explorateur (F-31 / SF-31-10).
 *
 * <p>Les deux refus du serveur sont dits **pendant la saisie** plutôt que découverts après l'envoi :
 * un nom de forme invalide, et un nom déjà pris.</p>
 */
@Component({
  selector: 'app-git-branch-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './git-branch-dialog.component.html',
  styleUrl: './git-branch-dialog.component.scss',
})
export class GitBranchDialogComponent {
  readonly data = inject<GitBranchDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<GitBranchDialogComponent, string>>(MatDialogRef);

  readonly name = signal('');

  /** Message d'erreur de saisie, ou `null` si le nom est acceptable. */
  readonly error = signal<string | null>(null);

  onNameChange(value: string): void {
    this.name.set(value);
    const trimmed = value.trim();
    if (!trimmed) {
      this.error.set(null);
      return;
    }
    if (!BRANCH_PATTERN.test(trimmed)) {
      this.error.set('Nom de branche invalide.');
      return;
    }
    if (this.data.existing.includes(trimmed)) {
      this.error.set('Cette branche existe déjà.');
      return;
    }
    this.error.set(null);
  }

  canCreate(): boolean {
    return this.name().trim().length > 0 && this.error() === null;
  }

  create(): void {
    if (this.canCreate()) {
      this.dialogRef.close(this.name().trim());
    }
  }
}
