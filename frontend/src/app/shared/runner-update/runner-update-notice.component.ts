import { Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RunnerHostOverview } from '../../core/models/atelier.models';
import {
  RunnerManualUpdateDialogComponent,
  RunnerManualUpdateDialogData,
} from './runner-manual-update-dialog.component';
import { platformFromOs, runnerVersionLabel, updateNotice } from './runner-update';

/**
 * **Le runner du client, dans son en-tête** (F-111 / SF-111-01), Forge et Vigie : sa version réelle et,
 * quand il y a lieu, « Mise à jour disponible — 1.0.0 → 1.1.0 », « Mise à jour requise » ou « mise à
 * jour manuelle une dernière fois » avec la commande exacte du système du poste.
 *
 * <p>Muet sur la mise à jour quand le runner est à jour ou que rien n'est comparable : l'en-tête
 * résume, il ne s'inquiète pas à la place de l'utilisateur.</p>
 */
@Component({
  selector: 'app-runner-update-notice',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (version(); as current) {
      <span class="runner-update__version">Runner <span class="runner-update__mono">{{ current }}</span></span>
    }
    @if (notice(); as n) {
      <span
        class="badge runner-update__badge"
        [class.badge--error]="n.tone === 'error'"
        [class.badge--warning]="n.tone === 'warning'"
        [class.badge--info]="n.tone === 'info'"
        [matTooltip]="notes()"
        [matTooltipDisabled]="!notes()"
        role="status"
      >
        <mat-icon aria-hidden="true">{{ n.manual ? 'download' : 'system_update_alt' }}</mat-icon>
        {{ n.label }}
      </span>
      @if (n.manual) {
        <button
          mat-button
          type="button"
          class="runner-update__how"
          (click)="showManual()"
          matTooltip="La commande qui télécharge le runner à jour pour le système de ce poste"
        >
          <mat-icon>terminal</mat-icon>
          Voir la commande
        </button>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .runner-update__version {
      font-size: 13px;
      color: var(--cg-text-secondary);
    }

    .runner-update__mono {
      font-family: var(--cg-font-mono);
    }

    .runner-update__badge mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
  `,
})
export class RunnerUpdateNoticeComponent {
  private readonly dialog = inject(MatDialog);

  readonly host = input.required<RunnerHostOverview>();

  readonly version = computed(() => runnerVersionLabel(this.host().runnerUpdate, this.host().runnerVersion));
  readonly notice = computed(() => updateNotice(this.host().runnerUpdate));
  /** Ce qu'apporte la version servie (F-111 / SF-111-03), en infobulle de la pastille. */
  readonly notes = computed(() => (this.host().runnerUpdate?.notes ?? []).join(' · '));

  showManual(): void {
    const update = this.host().runnerUpdate;
    if (!update) {
      return;
    }
    const data: RunnerManualUpdateDialogData = {
      hostName: this.host().name,
      update,
      platform: platformFromOs(this.host().os),
      origin: window.location.origin,
    };
    this.dialog.open(RunnerManualUpdateDialogComponent, {
      data,
      width: RunnerManualUpdateDialogComponent.DIALOG_WIDTH,
      maxWidth: '95vw',
    });
  }
}
