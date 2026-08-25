import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/**
 * Confirmation de retrait du jeton GitHub (F-31 / SF-31-01). Action réversible (le jeton peut être
 * re-saisi) mais confirmée via `MatDialog` (conforme au design system, jamais `window.confirm`).
 */
@Component({
  selector: 'app-remove-git-token-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './remove-git-token-dialog.component.html',
})
export class RemoveGitTokenDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<RemoveGitTokenDialogComponent, boolean>);

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
