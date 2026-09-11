import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** Le projet qu'on s'apprête à supprimer, et le poste sous lequel il vit (s'il en a un). */
export interface DeleteProjectDialogData {
  projectName: string;
  /** Nom du poste, quand le projet est rattaché — sert à nommer la machine qu'on ne touche pas. */
  hostName?: string | null;
  /** Chemin du projet sous la racine du poste, quand il vit sur une machine. */
  projectPath?: string | null;
}

/**
 * Confirmation de suppression d'un **projet** (F-69 / SF-69-02).
 *
 * <p>Ce dialogue a une seule raison d'être, et elle est rédactionnelle : quelqu'un qui hésite devant
 * un bouton « Supprimer » se demande exactement une chose — <b>est-ce que ça touche à mes
 * fichiers ?</b> La réponse doit être <b>sur l'écran</b>, pas dans une documentation. D'où deux
 * listes de même poids : ce qui part, et ce qui ne bouge pas.</p>
 *
 * <p>La portée est une décision du PO, non une contrainte technique : supprimer les fichiers d'un
 * client depuis une application web serait irréversible et illégitime. La gateway n'émet d'ailleurs
 * aucune commande vers la machine sur ce chemin, et un test le lui interdit (SF-69-01).</p>
 *
 * <p>Jamais {@code window.confirm}, que le design system interdit.</p>
 */
@Component({
  selector: 'app-delete-project-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './delete-project-dialog.component.html',
  styleUrl: './delete-project-dialog.component.scss',
})
export class DeleteProjectDialogComponent {
  readonly data = inject<DeleteProjectDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DeleteProjectDialogComponent, boolean>);

  /**
   * Comment nommer le dossier de la machine, quand on le connaît. On ne montre que le chemin
   * **relatif** déclaré sous la racine du poste : l'arborescence complète de la machine n'entre pas
   * en base, et n'a donc rien à faire à l'écran.
   */
  folderLabel(): string | null {
    const path = this.data.projectPath?.trim();
    if (!path) {
      return this.data.hostName ? `le dossier de « ${this.data.hostName} »` : null;
    }
    return `le dossier « ${path} »`;
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
