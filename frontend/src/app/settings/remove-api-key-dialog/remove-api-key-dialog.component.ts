import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/**
 * Confirmation de suppression de la clé BYOK. Action réversible (la clé peut être re-saisie) mais
 * confirmée via `MatDialog` (conforme au design system, jamais `window.confirm`).
 */
@Component({
  selector: 'app-remove-api-key-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './remove-api-key-dialog.component.html',
})
export class RemoveApiKeyDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<RemoveApiKeyDialogComponent, boolean>);

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
