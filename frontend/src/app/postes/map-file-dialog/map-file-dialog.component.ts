import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { GovernanceService } from '../../core/services/governance.service';
import {
  GovernanceMapFile,
  GovernanceMapFileContent,
} from '../../core/models/governance.models';

/** Ce qu'il faut pour ouvrir un fichier de la carte : le poste, et le fichier tel que relevé. */
export interface MapFileDialogData {
  hostRef: string;
  hostName: string;
  file: GovernanceMapFile;
}

/**
 * Un fichier de la carte, **ouvert** (F-92 / SF-92-03).
 *
 * <p>C'est le geste qui donne son sens à toute la feature : relire les VPN d'un client, ses
 * bastions, ses pièges — <b>sans ouvrir un terminal</b>. Le relevé de la carte dit <i>combien</i> ;
 * ce dialogue dit <b>quoi</b>.</p>
 *
 * <p><b>Le contenu n'est jamais interprété.</b> Il est rendu tel quel dans un bloc préformaté :
 * c'est le fichier d'un client, il peut contenir n'importe quoi, et rien n'autorise l'écran à le
 * traiter comme du balisage.</p>
 *
 * <p><b>Rien n'est inventé en cas d'échec</b> : ni contenu vide présenté comme une carte vide, ni
 * message rassurant. On dit l'échec, et on offre « Réessayer ».</p>
 */
@Component({
  selector: 'app-map-file-dialog',
  standalone: true,
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './map-file-dialog.component.html',
  styleUrl: './map-file-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapFileDialogComponent {
  readonly data = inject<MapFileDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<MapFileDialogComponent>);
  private readonly governance = inject(GovernanceService);

  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly content = signal<GovernanceMapFileContent | null>(null);

  constructor() {
    this.read();
  }

  /** Lit le contenu du fichier sur la machine. Rejouable par « Réessayer ». */
  read(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.governance.readMapFile(this.data.hostRef, this.data.file.path).subscribe({
      next: (content) => {
        this.content.set(content);
        this.loading.set(false);
      },
      error: () => {
        // Aucun contenu de repli : un fichier vide affiché à la place d'une erreur ferait croire à
        // une carte vide, ce qui est exactement le contresens que cette feature combat.
        this.content.set(null);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
