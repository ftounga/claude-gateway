import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

import { CopyBlockComponent } from '../../chat/copy-block/copy-block.component';
import { RunnerUpdateView } from '../../core/models/atelier.models';
import { RunnerHostPlatform } from '../../atelier/runner/runner-pairing-dialog.component';
import { RemedyCommand, runnerUpdateCommands } from '../../vigie/radar-verification/radar-verification';
import { CopyBlock } from '../copy-block.model';
import { manualUpdateText } from './runner-update';

/** Ce que le dialogue reçoit. */
export interface RunnerManualUpdateDialogData {
  hostName: string;
  update: RunnerUpdateView;
  platform: RunnerHostPlatform;
  origin: string;
}

/**
 * **La dernière mise à jour manuelle** (F-111 / SF-111-01, cadrage §6) : les runners installés avant le
 * lanceur ne peuvent pas se mettre à jour seuls. La transition est dite franchement, avec la commande
 * exacte du système du poste — celle que l'assistant de vérification (F-100) donnait déjà.
 */
@Component({
  selector: 'app-runner-manual-update-dialog',
  imports: [MatDialogModule, MatButtonModule, CopyBlockComponent],
  template: `
    <h2 mat-dialog-title>Mettre à jour le runner de {{ data.hostName }}</h2>
    <mat-dialog-content>
      <p class="manual-update__text">{{ text }}</p>
      @for (command of commands; track command.content) {
        <app-copy-block class="manual-update__command" [block]="block(command)"></app-copy-block>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .manual-update__text {
      margin: 0 0 var(--cg-space-3);
      font-size: 14px;
    }

    .manual-update__command {
      display: block;
      margin-bottom: var(--cg-space-2);
    }
  `,
})
export class RunnerManualUpdateDialogComponent {
  static readonly DIALOG_WIDTH = '640px';

  readonly data = inject<RunnerManualUpdateDialogData>(MAT_DIALOG_DATA);
  readonly text = manualUpdateText(this.data.update);
  readonly commands: RemedyCommand[] = runnerUpdateCommands(this.data.platform, this.data.origin);

  block(command: RemedyCommand): CopyBlock {
    return { type: 'code', language: null, title: command.title, content: command.content };
  }
}
