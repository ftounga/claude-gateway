import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { RadarExportOfferComponent } from '../radar-export/radar-export-offer.component';

export interface RemoveClientDialogData {
  /** Le client : l'export de son Radar est proposé avant de l'effacer (F-99 / SF-99-07). */
  hostId?: string;
  hostName: string;
  /** Le client est-il aussi dans la Forge ? Sinon le retrait est refusé : c'est son dernier espace. */
  inForge: boolean;
}

export interface RemoveClientDialogResult {
  confirmed: boolean;
  /** Effacer aussi son Radar — irréversible, jamais présumé. */
  purgeRadar: boolean;
}

/**
 * **Retirer un client de la Vigie** (F-106 / SF-106-02) : il disparaît de cet espace, rien n'est
 * supprimé ailleurs. L'effacement de son Radar est <b>proposé</b>, décoché par défaut (cadrage §3).
 */
@Component({
  selector: 'app-remove-client-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatIconModule, RadarExportOfferComponent],
  templateUrl: './remove-client-dialog.component.html',
  styleUrl: './remove-client-dialog.component.scss',
})
export class RemoveClientDialogComponent {
  readonly data = inject<RemoveClientDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject(MatDialogRef<RemoveClientDialogComponent, RemoveClientDialogResult>);

  readonly purgeRadar = signal(false);

  cancel(): void {
    this.dialogRef.close({ confirmed: false, purgeRadar: false });
  }

  confirm(): void {
    if (!this.data.inForge) {
      return;
    }
    this.dialogRef.close({ confirmed: true, purgeRadar: this.purgeRadar() });
  }
}
