import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import {
  GovernanceDepositAction,
  GovernanceDepositPlan,
} from '../../core/models/governance.models';

/** Ce que le dialogue reçoit : le paquet, le projet où il irait, et ce qu'il y écrirait. */
export interface DepositPreviewData {
  packageName: string;
  projectName: string;
  plan: GovernanceDepositPlan;
}

/**
 * L'annonce, avant l'écriture (F-51 / SF-51-05).
 *
 * C'est l'exigence centrale de la feature : **un paquet écrit sur la machine de l'utilisateur**, donc
 * l'écran dit ce qu'il va écrire et où **avant** qu'on l'active. Un dialogue qu'il faut confirmer,
 * et non un texte sur une carte qu'on peut ne pas lire (arbitrage E2).
 *
 * Trois verdicts, et le troisième compte autant que les deux autres : quand le projet n'a pas pu être
 * lu — machine éteinte —, on l'écrit noir sur blanc au lieu de promettre des créations qu'on n'est pas
 * sûr de faire. L'activation reste possible : les règles et les contrôles s'appliquent sans disque.
 */
@Component({
  selector: 'app-deposit-preview-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './deposit-preview-dialog.component.html',
  styleUrl: './deposit-preview-dialog.component.scss',
})
export class DepositPreviewDialogComponent {
  readonly data = inject<DepositPreviewData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<DepositPreviewDialogComponent, boolean>);

  /** Nombre de fichiers qui seront réellement créés — ce que l'utilisateur veut voir en premier. */
  get createdCount(): number {
    return this.data.plan.entries.filter((entry) => entry.action === 'CREATE').length;
  }

  /** Fichiers déjà présents, qui ne seront pas touchés. */
  get keptCount(): number {
    return this.data.plan.entries.filter((entry) => entry.action === 'KEEP').length;
  }

  /** Le verdict, en clair. Un mot juste vaut mieux qu'un code. */
  actionLabel(action: GovernanceDepositAction): string {
    switch (action) {
      case 'CREATE':
        return 'sera créé';
      case 'KEEP':
        return 'déjà présent — laissé tel quel';
      default:
        return 'indéterminé';
    }
  }

  actionIcon(action: GovernanceDepositAction): string {
    switch (action) {
      case 'CREATE':
        return 'note_add';
      case 'KEEP':
        return 'lock';
      default:
        return 'help_outline';
    }
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    this.dialogRef.close(true);
  }
}
