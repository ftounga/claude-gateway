import { HttpErrorResponse } from '@angular/common/http';
import { Component, Injector, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RunnerHostOverview, RunnerUpdateProgress } from '../../core/models/atelier.models';
import { RunnerUpdateService } from '../../core/services/runner-update.service';
import {
  RunnerForceUpdateDialogComponent,
  RunnerForceUpdateDialogData,
} from './runner-force-update-dialog.component';
import {
  RunnerManualUpdateDialogComponent,
  RunnerManualUpdateDialogData,
} from './runner-manual-update-dialog.component';
import { platformFromOs, progressLine, runnerVersionLabel, updateNotice } from './runner-update';

/**
 * **Le runner du client, dans son en-tête** (F-111), Forge et Vigie : sa version réelle et, quand il y a
 * lieu, « Mise à jour disponible — 1.0.0 → 1.1.0 » avec **Mettre à jour** (SF-111-04), « Mise à jour
 * requise », ou « mise à jour manuelle une dernière fois » avec la commande exacte du système du poste
 * (SF-111-01) ; puis où en est la mise à jour — téléchargement, attente de la fin des activités avec
 * **Forcer**, redémarrage, résultat.
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
    @if (line(); as l) {
      <span
        class="badge runner-update__badge runner-update__progress"
        [class.badge--info]="l.tone === 'info'"
        [class.badge--warning]="l.tone === 'warning'"
        [class.badge--success]="l.tone === 'success'"
        [class.badge--error]="l.tone === 'error'"
        role="status"
      >
        <mat-icon aria-hidden="true">{{ l.active ? 'sync' : l.tone === 'success' ? 'check_circle' : 'error_outline' }}</mat-icon>
        {{ l.text }}
      </span>
      @if (l.waiting) {
        <button
          mat-stroked-button
          type="button"
          class="runner-update__force"
          [disabled]="sending()"
          (click)="force()"
          matTooltip="Redémarrer tout de suite, en interrompant ce qui tourne sur le poste"
        >
          <mat-icon>bolt</mat-icon>
          Forcer
        </button>
      }
    }
    @if (!line()?.active) {
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
        } @else if (canUpdate()) {
          <button
            mat-flat-button
            color="primary"
            type="button"
            class="runner-update__update"
            [disabled]="sending()"
            (click)="update()"
            matTooltip="Le runner télécharge la nouvelle version, vérifie sa signature, attend la fin de ce qui tourne et redémarre"
          >
            <mat-icon>system_update_alt</mat-icon>
            Mettre à jour
          </button>
        }
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
  /**
   * Le service n'est résolu qu'au clic : l'en-tête s'affiche dans des écrans (et leurs tests) qui ne
   * fournissent pas de client HTTP tant que personne ne met à jour.
   */
  private readonly injector = inject(Injector);
  private readonly snackBar = inject(MatSnackBar);

  readonly host = input.required<RunnerHostOverview>();
  /** Le poste est joignable maintenant (présence datée de l'écran). */
  readonly online = input(false);

  /** Réponse de la gateway au dernier clic, en attendant le prochain rafraîchissement de la vue. */
  private readonly answered = signal<RunnerUpdateProgress | null>(null);
  readonly sending = signal(false);

  readonly version = computed(() => runnerVersionLabel(this.host().runnerUpdate, this.host().runnerVersion));
  readonly notice = computed(() => updateNotice(this.host().runnerUpdate));
  /** Ce qu'apporte la version servie (F-111 / SF-111-03), en infobulle de la pastille. */
  readonly notes = computed(() => (this.host().runnerUpdate?.notes ?? []).join(' · '));
  readonly progress = computed(() => newest(this.host().runnerUpdate?.progress ?? null, this.answered()));
  readonly line = computed(() => progressLine(this.progress()));
  readonly canUpdate = computed(() => !!this.host().runnerUpdate?.oneClick && this.online() && !!this.host().id);

  update(): void {
    this.send(false);
  }

  force(): void {
    const data: RunnerForceUpdateDialogData = { hostName: this.host().name, detail: this.progress()?.detail ?? null };
    this.dialog.open(RunnerForceUpdateDialogComponent, { data, width: '480px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.send(true);
        }
      });
  }

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

  private send(force: boolean): void {
    const hostId = this.host().id;
    if (!hostId || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.injector.get(RunnerUpdateService).request(hostId, force).subscribe({
      next: (progress) => {
        this.sending.set(false);
        this.answered.set(progress);
        this.snackBar.open(force ? 'Mise à jour forcée : le runner redémarre.'
          : 'Mise à jour lancée : le runner télécharge et vérifie la nouvelle version.', 'Fermer', { duration: 5000 });
      },
      error: (err: unknown) => {
        this.sending.set(false);
        this.snackBar.open(errorMessage(err), 'Fermer', { duration: 8000 });
      },
    });
  }
}

/** La plus récente des deux lectures d'une même mise à jour (vue rafraîchie, réponse au clic). */
function newest(fromView: RunnerUpdateProgress | null, answered: RunnerUpdateProgress | null): RunnerUpdateProgress | null {
  if (!answered) {
    return fromView;
  }
  if (!fromView) {
    return answered;
  }
  return Date.parse(answered.updatedAt) > Date.parse(fromView.updatedAt) ? answered : fromView;
}

function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse && err.error && typeof err.error.message === 'string') {
    return err.error.message;
  }
  return 'La mise à jour n’a pas pu être lancée. Réessayez dans un instant.';
}
