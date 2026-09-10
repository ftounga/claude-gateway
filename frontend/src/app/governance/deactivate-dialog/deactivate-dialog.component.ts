import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/** Ce que le dialogue a besoin de savoir : le nom du paquet qu'on éteint. */
export interface DeactivateDialogData {
  packageName: string;
}

/**
 * Confirmation de désactivation d'un paquet sur un projet (F-51 / SF-51-05).
 *
 * <p>Le dialogue existe surtout pour dire une chose : <b>les fichiers déjà déposés restent</b>. Sans
 * cette phrase, un utilisateur peut croire que désactiver nettoie le projet — et ne pas comprendre
 * pourquoi son `STATE.md` est toujours là. Le produit ne supprime jamais un fichier utilisateur sur
 * un décochage.</p>
 */
@Component({
  selector: 'app-deactivate-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './deactivate-dialog.component.html',
})
export class DeactivateDialogComponent {
  readonly data = inject<DeactivateDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DeactivateDialogComponent, boolean>);

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
