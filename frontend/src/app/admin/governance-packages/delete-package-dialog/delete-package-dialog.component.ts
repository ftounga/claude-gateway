import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/** Le nom du paquet qu'on s'apprête à supprimer. */
export interface DeletePackageDialogData {
  packageName: string;
}

/**
 * Confirmation de suppression d'un paquet **non publié** (F-51 / SF-51-06).
 *
 * <p>Suppression définitive, donc confirmée par un dialogue — jamais par {@code window.confirm}, que
 * le design system interdit. Elle n'est proposée que sur un brouillon : le backend refuse la
 * suppression d'un paquet publié, et la dépublication est le geste réversible qui le retire du
 * catalogue en laissant vivre ce qui l'applique.</p>
 */
@Component({
  selector: 'app-delete-package-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './delete-package-dialog.component.html',
})
export class DeletePackageDialogComponent {
  readonly data = inject<DeletePackageDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DeletePackageDialogComponent, boolean>);

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
