import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import {
  CalendarError, CalendarFeed, GOOGLE_ICAL_HELP_URL, JarvisCalendarClient, calendarErrorMessage, calendarStatusLabel,
} from './jarvis-calendar';

/**
 * **Le volet « Mes agendas »** (F-243 / SF-243-01) — dans les réglages de `/jarvis`, à côté de « Mes boîtes ».
 *
 * Un pas-à-pas en trois lignes (paramètres de l'agenda Google, « Adresse secrète au format iCal », la coller), le
 * formulaire adresse (champ masqué : c'est un secret) / libellé, et la liste des agendas avec leur état, « à jour à
 * hh:mm », `…last4`, « Tester » et « Retirer » (confirmation). Le parent ne l'affiche **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-calendars',
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <section class="jc" aria-label="Mes agendas">
      <header class="jc__head">
        <h2 class="jc__title">Mes agendas</h2>
        <button mat-icon-button type="button" class="jc__close" aria-label="Fermer mes agendas" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      <p class="jc__hint">
        Jarvis lit vos agendas Google toutes les 15 minutes, sans IA, pour vous dire votre journée. Lecture seule.
        Jamais en présentation.
      </p>
      @if (loading()) {
        <p class="jc__hint">Chargement…</p>
      } @else {
        <ul class="jc__list">
          @for (f of feeds(); track f.id) {
            <li class="jc__feed">
              <span class="jc__label">{{ f.label }}</span>
              <span class="jc__status" [class.jc__status--ko]="f.status !== 'OK'">{{ statusLabel(f) }}</span>
              <span class="jc__meta">…{{ f.secretLast4 }} · {{ f.eventCount }} rendez-vous@if (f.lastOkAt) { · à jour à {{ f.lastOkAt | date: 'HH:mm' }} }</span>
              @if (f.lastError) { <span class="jc__error" role="alert">{{ f.lastError }}</span> }
              <span class="jc__actions">
                <button mat-button type="button" class="jc__test" [disabled]="busy()" (click)="test(f)">Tester</button>
                <button mat-button type="button" color="warn" class="jc__remove" [disabled]="busy()" (click)="remove(f)">Retirer</button>
              </span>
            </li>
          }
        </ul>
        <ol class="jc__steps">
          <li>Dans Google Agenda, ouvrez les paramètres de l’agenda (⋮ à côté de son nom).</li>
          <li>Copiez l’« Adresse secrète au format iCal » (section « Intégrer l’agenda »,
            <a class="jc__link" [href]="helpUrl" target="_blank" rel="noopener noreferrer">aide Google</a>).</li>
          <li>Collez-la ici : Jarvis la lit, puis la garde chiffrée.</li>
        </ol>
        <form class="jc__form" (submit)="$event.preventDefault(); link(url, label.value)">
          <mat-form-field appearance="outline" class="jc__field" subscriptSizing="dynamic">
            <mat-label>Adresse secrète au format iCal</mat-label>
            <input matInput #url type="password" autocomplete="off" maxlength="1024" name="url" class="jc__url" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="jc__field jc__field--label" subscriptSizing="dynamic">
            <mat-label>Libellé</mat-label>
            <input matInput #label maxlength="40" name="label" placeholder="Pro, Perso…" />
          </mat-form-field>
          <button mat-flat-button color="primary" type="submit" class="jc__add" [disabled]="busy()">
            {{ busy() ? 'Lecture en cours…' : 'Ajouter' }}
          </button>
        </form>
        @if (error()) { <p class="jc__error" role="alert">{{ error() }}</p> }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jc {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(520px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jc__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jc__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jc__close, .jc__actions button { color: var(--cg-cream); }
    .jc__hint { margin: 0; font-size: 13px; opacity: .8; }
    .jc__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-2); }
    .jc__feed { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-1) var(--cg-space-2);
      padding: var(--cg-space-2); border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 4px; }
    .jc__label { flex: 1 1 160px; font-weight: 600; font-size: 14px; }
    .jc__status { font-size: 12px; padding: 0 var(--cg-space-1); border: 1px solid color-mix(in srgb, var(--cg-cream) 35%, transparent); border-radius: 4px; }
    .jc__status--ko, .jc__error { color: var(--cg-orange-2); border-color: var(--cg-orange-2); }
    .jc__meta { font-family: var(--cg-font-mono); font-size: 12px; opacity: .7; }
    .jc__error { margin: 0; font-size: 13px; }
    .jc__actions { display: flex; flex-wrap: wrap; margin-left: auto; }
    .jc__steps { margin: 0; padding-left: var(--cg-space-4); font-size: 13px; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .jc__link { color: var(--cg-orange-2); }
    .jc__form { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jc__field { flex: 1 1 100%; min-width: 0; }
    .jc__field--label { flex-basis: 40%; }
    @media (max-width: 767px) {
      .jc { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisCalendarsComponent implements OnInit {
  private readonly client = inject(JarvisCalendarClient);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);

  readonly closed = output<void>();

  readonly helpUrl = GOOGLE_ICAL_HELP_URL;
  readonly feeds = signal<CalendarFeed[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');

  ngOnInit(): void {
    this.reload();
  }

  statusLabel(f: CalendarFeed): string {
    return calendarStatusLabel(f.status);
  }

  reload(): void {
    this.client.list().subscribe({
      next: (list) => {
        this.loading.set(false);
        this.feeds.set(list);
      },
      error: (err: CalendarError) => {
        this.loading.set(false);
        this.error.set(calendarErrorMessage(err));
      },
    });
  }

  /** « Ajouter » : la passerelle lit l'agenda avant d'écrire ; le champ de l'adresse est vidé quoi qu'il arrive. */
  link(url: HTMLInputElement, label: string): void {
    const secret = url.value.trim();
    if (!secret) {
      this.error.set('Collez l’adresse secrète au format iCal de l’agenda.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.client.link(secret, label.trim()).subscribe({
      next: (feed) => {
        url.value = '';
        this.busy.set(false);
        this.snack.open(`Agenda ajouté : ${feed.eventCount} rendez-vous lus.`, undefined, { duration: 3000 });
        this.reload();
      },
      error: (err: CalendarError) => {
        url.value = '';
        this.busy.set(false);
        this.error.set(calendarErrorMessage(err));
      },
    });
  }

  test(f: CalendarFeed): void {
    this.busy.set(true);
    this.client.test(f.id).subscribe({
      next: (fresh) => {
        this.busy.set(false);
        this.feeds.update((list) => list.map((x) => (x.id === fresh.id ? fresh : x)));
        this.snack.open(fresh.status === 'OK' && !fresh.lastError ? 'L’agenda répond.' : (fresh.lastError ?? 'L’agenda ne répond pas.'),
          'OK', { duration: 4000 });
      },
      error: (err: CalendarError) => {
        this.busy.set(false);
        this.snack.open(calendarErrorMessage(err), 'OK', { duration: 6000 });
      },
    });
  }

  remove(f: CalendarFeed): void {
    const data: ConfirmDialogData = {
      title: 'Retirer cet agenda ?',
      message: `Jarvis oubliera l’agenda « ${f.label} » et ses rendez-vous gardés en cache. Pour couper l’adresse chez `
        + `Google aussi, réinitialisez-la dans les paramètres de l’agenda.`,
      confirmLabel: 'Retirer',
    };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) {
        return;
      }
      this.client.remove(f.id).subscribe({
        next: () => {
          this.feeds.update((list) => list.filter((x) => x.id !== f.id));
          this.snack.open('Agenda retiré.', undefined, { duration: 3000 });
        },
        error: (err: CalendarError) => this.snack.open(calendarErrorMessage(err), 'OK', { duration: 6000 }),
      });
    });
  }
}
