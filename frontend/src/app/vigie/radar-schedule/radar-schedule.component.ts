import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RadarSchedule } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import {
  RadarScheduleDialogComponent,
  RadarScheduleDialogData,
} from './radar-schedule-dialog.component';
import { scheduleSentence } from './radar-schedule';

/**
 * **La synchro du soir dans l'en-tête d'un client** (F-100 / SF-100-07) : activée ou non, à quelle heure,
 * la prochaine exécution — et *Régler*.
 *
 * <p><b>Silencieuse</b> quand elle ne peut pas lire le réglage (droit retiré, gateway antérieure) : l'en-tête
 * résume, il ne diagnostique pas.</p>
 */
@Component({
  selector: 'app-radar-schedule',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (schedule(); as current) {
      <span class="radar-schedule" [class.radar-schedule--attention]="sentence()?.attention">
        <mat-icon class="radar-schedule__icon" aria-hidden="true">nightlight</mat-icon>
        <span class="radar-schedule__text">{{ sentence()?.text }}</span>
        <button
          mat-button
          type="button"
          class="radar-schedule__edit"
          (click)="edit()"
          [attr.aria-label]="'Régler la synchro du soir de ' + hostName()"
          matTooltip="Activer ou désactiver la synchro du soir, choisir l'heure"
        >
          <mat-icon>schedule</mat-icon>
          Régler
        </button>
      </span>
    }
  `,
  styles: `
    .radar-schedule {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      font-size: 13px;
      color: var(--cg-text-secondary);
    }

    /* §12 : un créneau manqué s'écrit en ambre, jamais en aplat. */
    .radar-schedule--attention .radar-schedule__text {
      color: #F9A825;
      font-weight: 500;
    }

    .radar-schedule__icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .radar-schedule__edit {
      min-width: 0;
    }
  `,
})
export class RadarScheduleComponent {
  private readonly radar = inject(RadarService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly hostId = input.required<string>();
  readonly hostName = input('');

  readonly schedule = signal<RadarSchedule | null>(null);
  readonly sentence = computed(() => {
    const current = this.schedule();
    return current ? scheduleSentence(current) : null;
  });

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      untracked(() => this.load(hostId));
    });
  }

  edit(): void {
    const hostId = this.hostId();
    this.dialog
      .open<RadarScheduleDialogComponent, RadarScheduleDialogData, RadarSchedule>(RadarScheduleDialogComponent, {
        data: { hostId, hostName: this.hostName(), schedule: this.schedule() },
        width: RadarScheduleDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((saved) => {
        if (!saved || hostId !== this.hostId()) {
          return;
        }
        this.schedule.set(saved);
        this.snackBar.open(saved.enabled ? `Synchro du soir activée à ${saved.syncTime}.` : 'Synchro du soir désactivée.',
          'Fermer', { duration: 5000 });
      });
  }

  private load(hostId: string): void {
    this.schedule.set(null);
    this.radar.schedule(hostId).subscribe({
      next: (schedule) => {
        if (hostId === this.hostId()) {
          this.schedule.set(schedule);
        }
      },
      error: () => undefined,
    });
  }
}
