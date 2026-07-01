import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Données passées au dialog de suppression de compte. */
export interface DeleteAccountDialogData {
  email: string;
}

/**
 * Confirmation destructive de suppression de compte. Garde-fou contre une action irréversible :
 * la confirmation n'est active que si l'utilisateur ressaisit exactement l'e-mail de son compte
 * (comparaison insensible à la casse et aux espaces). Conforme au design system (MatDialog,
 * action destructive `color="warn"`, jamais `window.confirm`).
 */
@Component({
  selector: 'app-delete-account-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './delete-account-dialog.component.html',
  styleUrl: './delete-account-dialog.component.scss',
})
export class DeleteAccountDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<DeleteAccountDialogComponent, boolean>);
  readonly data = inject<DeleteAccountDialogData>(MAT_DIALOG_DATA);

  readonly confirmation = signal('');

  /** Vrai quand la saisie correspond à l'e-mail du compte. */
  matches(): boolean {
    return this.confirmation().trim().toLowerCase() === this.data.email.trim().toLowerCase();
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    if (this.matches()) {
      this.dialogRef.close(true);
    }
  }
}
