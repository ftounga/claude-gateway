import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { HostProjectSummary } from '../../core/models/atelier.models';

/** Le poste visé par le coupe-circuit, et ce qui vit dessous. */
export interface KillHostDialogData {
  hostName: string;
  /** Les projets du poste, tels que la gateway les rend. Ils sont **nommés**, jamais comptés seuls. */
  projects: HostProjectSummary[];
}

/** Un projet tel que le dialogue le présente : son nom, et s'il va réellement changer de cible. */
export interface KillHostProjectLine {
  name: string;
  /** Vrai quand le projet est **déjà** en bac à sable : rien ne changera pour lui. */
  alreadySandbox: boolean;
}

/**
 * Confirmation du **coupe-circuit** d'un poste depuis `/forge` (F-82 / SF-82-02).
 *
 * <p><b>Ce que cette confirmation répare.</b> Le coupe-circuit existait (SF-38-08, porté au poste
 * par SF-48-01) mais son bouton ne vivait que dans l'en-tête d'un terminal — donc introuvable sur un
 * poste connecté <b>sans aucun projet</b>, qui n'a pas de terminal. Et son libellé, « Couper la
 * liaison avec la machine », laissait croire deux choses fausses : que l'effet s'arrête à la
 * liaison, et que le programme s'arrête.</p>
 *
 * <p>D'où les <b>trois</b> blocs, et l'ordre dans lequel ils viennent :</p>
 * <ol>
 *   <li><b>Ce qui change tout de suite</b> — la liaison, les jetons, et les projets ramenés au bac à
 *       sable, <b>nommés un par un</b>. Un compte ne dit pas ce qu'on perd ; un nom, si.</li>
 *   <li><b>Ce qui ne s'arrête pas</b> — le runner continue de tourner sur la machine, tentera de se
 *       reconnecter, et sera refusé.</li>
 *   <li><b>Comment l'arrêter vraiment</b> — sur la machine. L'application <b>dit</b> comment ; elle
 *       ne le fait pas : ce serait une extinction à distance sur le poste d'un client, et le produit
 *       ne s'arroge pas ce pouvoir (hors périmètre F-82).</li>
 * </ol>
 *
 * <p>Ce dialogue ne change <b>rien</b> à ce que fait le coupe-circuit : aucun appel n'en part, il
 * rend un booléen. La carte appelle.</p>
 */
@Component({
  selector: 'app-kill-host-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './kill-host-dialog.component.html',
  styleUrl: './kill-host-dialog.component.scss',
})
export class KillHostDialogComponent {
  readonly data = inject<KillHostDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<KillHostDialogComponent, boolean>);

  /**
   * Les projets du poste, **nommés**. Un projet dont la cible est inconnue (champ absent d'une
   * version de gateway plus ancienne) est listé sans mention plutôt que rangé à tort dans « déjà en
   * bac à sable » — on ne marque que ce qu'on sait.
   */
  readonly lines: KillHostProjectLine[] = (this.data.projects ?? []).map((project) => ({
    name: project.name,
    alreadySandbox: project.executionTarget === 'SANDBOX',
  }));

  /** Le cas vécu par le PO : une machine branchée qui ne porte aucun projet. */
  hasNoProject(): boolean {
    return this.lines.length === 0;
  }

  /** Les projets qui vont réellement changer de cible — ceux qui ne sont pas déjà au bac à sable. */
  returningCount(): number {
    return this.lines.filter((line) => !line.alreadySandbox).length;
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
