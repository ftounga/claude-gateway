import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** Ce que le dialogue reçoit. */
export interface RunnerForceUpdateDialogData {
  hostName: string;
  /** Les activités en cours, telles que le runner les nomme (« commande, capture »). */
  detail: string | null;
}

/**
 * **Forcer la mise à jour** (F-111 / SF-111-04) : un geste destructif — les commandes, captures ou synchros
 * en cours sur le poste sont interrompues, comme par `Ctrl+C`. Il se confirme donc.
 */
@Component({
  selector: 'app-runner-force-update-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Forcer la mise à jour de {{ data.hostName }} ?</h2>
    <mat-dialog-content>
      <p class="force-update__text">
        Le runner attend la fin {{ data.detail ? '(' + data.detail + ' en cours)' : 'des activités en cours' }}.
        Forcer le redémarre tout de suite : ce qui tourne sur le poste est interrompu, comme avec Ctrl+C.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">Attendre</button>
      <button mat-flat-button color="warn" type="button" [mat-dialog-close]="true">Forcer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .force-update__text {
      margin: 0;
      font-size: 14px;
    }
  `,
})
export class RunnerForceUpdateDialogComponent {
  readonly data = inject<RunnerForceUpdateDialogData>(MAT_DIALOG_DATA);
}
