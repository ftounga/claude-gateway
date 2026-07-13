import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Données du dialogue de saisie (renommage, nouveau dossier). */
export interface TextPromptDialogData {
  title: string;
  label: string;
  confirmLabel: string;
  initialValue?: string;
  hint?: string;
}

/**
 * Dialogue de saisie d'une chaîne (renommage d'un fichier, nom d'un nouveau dossier) pour
 * l'explorateur de l'Atelier (SF-28-15). Remplace `window.prompt` (interdit par le design system) :
 * saisie via `MatDialog` + `matInput`. Renvoie la valeur saisie (nettoyée) ou `undefined` si annulé.
 */
@Component({
  selector: 'app-text-prompt-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './text-prompt-dialog.component.html',
})
export class TextPromptDialogComponent {
  private readonly dialogRef =
    inject<MatDialogRef<TextPromptDialogComponent, string>>(MatDialogRef);
  readonly data = inject<TextPromptDialogData>(MAT_DIALOG_DATA);

  readonly value = signal(this.data.initialValue ?? '');

  cancel(): void {
    this.dialogRef.close();
  }

  /** Valide : renvoie la valeur nettoyée, ou ne ferme pas si elle est vide. */
  confirm(): void {
    const trimmed = this.value().trim();
    if (trimmed.length === 0) {
      return;
    }
    this.dialogRef.close(trimmed);
  }
}
