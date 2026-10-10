import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  NotificationEventOption,
  NotificationPreferencesService,
  browserTimeZone,
} from '../../core/services/notification-preferences.service';

/**
 * **Ce qui vous prévient** (F-185 / SF-185-06) : une case par événement et des heures calmes. Les
 * événements critiques — autorisation, question, rappel — restent cochés et grisés : ils attendent
 * une décision (D5). Un événement coupé reste dans la cloche ; il ne fait simplement plus sonner.
 */
@Component({
  selector: 'app-notification-preferences',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule],
  template: `
    @if (loaded()) {
      <section class="prefs" aria-label="Ce qui vous prévient">
        <h3 class="prefs__title">Ce qui vous prévient</h3>
        <p class="prefs__text">Un événement décoché reste dans la cloche, mais ne fait plus sonner vos appareils.</p>
        <ul class="prefs__events">
          @for (event of events(); track event.code) {
            <li>
              <mat-checkbox class="prefs__event" [checked]="event.critical || !muted().has(event.code)"
                [disabled]="event.critical" (change)="toggle(event, $event.checked)">
                {{ event.title }}
                @if (event.critical) {
                  <span class="prefs__always">toujours</span>
                }
              </mat-checkbox>
            </li>
          }
        </ul>
        <h3 class="prefs__title">Heures calmes</h3>
        <p class="prefs__text">Rien ne sonne pendant cette plage, sauf ce qui attend une décision.</p>
        <div class="prefs__quiet">
          <label>De <input class="prefs__from" type="time" [(ngModel)]="quietFrom" name="quietFrom"></label>
          <label>à <input class="prefs__to" type="time" [(ngModel)]="quietTo" name="quietTo"></label>
          <button mat-button type="button" class="prefs__none" (click)="quietFrom = ''; quietTo = ''">Aucune</button>
        </div>
        <div class="prefs__actions">
          <button mat-flat-button color="primary" type="button" class="prefs__save" [disabled]="saving()" (click)="save()">
            Enregistrer
          </button>
        </div>
      </section>
    }
  `,
  styles: `
    .prefs {
      margin-top: var(--cg-space-4, 16px);
      padding-top: var(--cg-space-3, 12px);
      border-top: 1px solid var(--cg-divider);
    }

    .prefs__title {
      font-family: var(--cg-font-heading);
      font-weight: 600;
      font-size: 15px;
      color: var(--cg-text-primary);
      margin: 0 0 var(--cg-space-1, 4px);
    }

    .prefs__text {
      color: var(--cg-text-secondary);
      font-size: 13px;
      margin: 0 0 var(--cg-space-2, 8px);
    }

    .prefs__events {
      list-style: none;
      margin: 0 0 var(--cg-space-3, 12px);
      padding: 0;
    }

    .prefs__always {
      margin-left: var(--cg-space-1, 4px);
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .prefs__quiet {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2, 8px);
      margin-bottom: var(--cg-space-3, 12px);
      color: var(--cg-text-primary);
      font-size: 14px;
    }

    .prefs__quiet input {
      margin-left: var(--cg-space-1, 4px);
      padding: var(--cg-space-1, 4px);
      border: 1px solid var(--cg-divider);
      border-radius: 6px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font: inherit;
    }
  `,
})
export class NotificationPreferencesComponent implements OnInit {
  private readonly preferences = inject(NotificationPreferencesService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly loaded = signal(false);
  protected readonly saving = signal(false);
  protected readonly events = signal<NotificationEventOption[]>([]);
  protected readonly muted = signal<Set<string>>(new Set());
  protected quietFrom = '';
  protected quietTo = '';

  ngOnInit(): void {
    this.preferences.get().subscribe({
      next: (prefs) => {
        this.events.set(prefs.events);
        this.muted.set(new Set(prefs.mutedEvents));
        this.quietFrom = prefs.quietFrom ?? '';
        this.quietTo = prefs.quietTo ?? '';
        this.loaded.set(true);
      },
      // Préférences illisibles : la carte d'activation reste, ce bloc ne s'affiche pas.
      error: () => this.loaded.set(false),
    });
  }

  protected toggle(event: NotificationEventOption, checked: boolean): void {
    if (event.critical) {
      return;
    }
    const next = new Set(this.muted());
    if (checked) {
      next.delete(event.code);
    } else {
      next.add(event.code);
    }
    this.muted.set(next);
  }

  protected save(): void {
    this.saving.set(true);
    this.preferences
      .save({
        mutedEvents: [...this.muted()],
        quietFrom: this.quietFrom || null,
        quietTo: this.quietTo || null,
        timeZone: browserTimeZone(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.snackBar.open('Préférences enregistrées.', 'Fermer', { duration: 4000 });
        },
        error: (error: HttpErrorResponse) => {
          this.saving.set(false);
          const message = (error.error as { message?: string } | null)?.message ?? 'Enregistrement impossible.';
          this.snackBar.open(message, 'Fermer', { duration: 6000 });
        },
      });
  }
}
