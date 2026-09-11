import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** Le poste visé, et ce qui vit encore dessous. */
export interface DeleteHostDialogData {
  hostName: string;
  /** Nombre de projets encore rattachés. Au-dessus de zéro, le dialogue devient un refus. */
  remainingProjects: number;
}

/**
 * Suppression d'un **poste** (F-69 / SF-69-02) : confirmation, **ou refus**.
 *
 * <p>Décision du PO : la suppression d'un poste est <b>refusée tant qu'il reste des projets</b>. Pas
 * de cascade — elle effacerait des conversations que l'utilisateur ne voyait même plus. Le refus
 * l'oblige à <b>regarder ce qu'il jette</b>.</p>
 *
 * <p>Un seul composant porte les deux états, et c'est délibéré : proposer un bouton qui refusera à
 * coup sûr serait une fausse promesse, et créer deux dialogues séparés ferait deux textes à tenir
 * d'accord. Le refus dit <b>combien</b> de projets restent et <b>où</b> les trouver — ils sont
 * listés sur la carte, juste derrière ce dialogue.</p>
 *
 * <p>La gateway refuse de son côté (409) : l'écran peut être en retard d'un projet créé dans un
 * autre onglet, le serveur non.</p>
 */
@Component({
  selector: 'app-delete-host-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './delete-host-dialog.component.html',
  styleUrl: './delete-host-dialog.component.scss',
})
export class DeleteHostDialogComponent {
  readonly data = inject<DeleteHostDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DeleteHostDialogComponent, boolean>);

  /** Vrai quand le poste porte encore des projets : le dialogue n'est alors pas une confirmation. */
  blocked(): boolean {
    return this.data.remainingProjects > 0;
  }

  /** « 1 projet » / « 3 projets » — le compte est l'information utile du refus. */
  projectsLabel(): string {
    const count = this.data.remainingProjects;
    return count === 1 ? '1 projet' : `${count} projets`;
  }

  /** Où sont ces projets : sur la carte, juste derrière ce dialogue. Un refus doit être actionnable. */
  whereToFindThem(): string {
    const listed = this.data.remainingProjects === 1 ? 'Il est listé' : 'Ils sont listés';
    return `${listed} sur la carte de « ${this.data.hostName} », juste derrière ce message.`;
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    if (this.blocked()) {
      return;
    }
    this.dialogRef.close(true);
  }
}
