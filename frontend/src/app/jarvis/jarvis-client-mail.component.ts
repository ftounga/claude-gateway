import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  ClientMailClient, ClientMailSettings, IgnoreOffer, IgnoreRule, JarvisClientMailClient, ignoreKindLabel,
  settingsError,
} from './jarvis-client-mail';

/**
 * **Le volet « Mails de vos clients »** (F-249 / SF-249-07, D16) — dans les réglages de `/jarvis`.
 *
 * Par client (poste) : le dossier où glisser les mails Outlook, l'état du runner (à jour ou à mettre à jour), le
 * dernier dépôt, le nombre de mails gardés, le rappel du soir (oui/non). Les réglages communs (heure du rappel,
 * répétitions, intervalle, jours ouvrés, alerte avant réunion). Les mails répétitifs proposés (« Ignorer » / « Garder »)
 * et les règles actives (« Retirer »). Le parent ne l'affiche **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-client-mail',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSlideToggleModule],
  template: `
    <section class="jc" aria-label="Mails de vos clients">
      <header class="jc__head">
        <h2 class="jc__title">Mails de vos clients</h2>
        <button mat-icon-button type="button" class="jc__close" aria-label="Fermer les mails de vos clients"
                (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      <p class="jc__hint">
        Glissez vos mails depuis Outlook dans le dossier du poste : le runner les envoie à Jarvis en arrière-plan, une
        seule fois. Jarvis les trie, les rattache à vos sujets et vous prévient.
      </p>
      @if (loading()) {
        <p class="jc__hint">Chargement…</p>
      } @else {
        <ul class="jc__list">
          @for (c of clients(); track c.hostId) {
            <li class="jc__client">
              <span class="jc__name">{{ c.name }}</span>
              @if (c.upToDate) {
                <span class="jc__badge">runner à jour</span>
              } @else {
                <span class="jc__badge jc__badge--ko">runner à mettre à jour</span>
              }
              <span class="jc__folder">{{ c.folder }}</span>
              <span class="jc__meta">
                {{ c.mails }} mail{{ c.mails > 1 ? 's' : '' }}
                @if (c.lastDepositAt) { · dernier dépôt le {{ c.lastDepositAt | date: 'dd/MM HH:mm' }} }
                @if (c.depositedToday) { · déposé aujourd'hui }
              </span>
              <mat-slide-toggle class="jc__toggle" [checked]="c.active" [disabled]="busy()"
                                (change)="setActive(c, $event.checked)">Rappel du soir</mat-slide-toggle>
              @for (o of offersOf(c.hostId); track o.id) {
                <div class="jc__offer">
                  <span class="jc__offer-text">{{ kindLabel(o) }} répétitif ({{ o.occurrences }}) : {{ o.sample }}</span>
                  <span class="jc__actions">
                    <button mat-button type="button" class="jc__ignore" [disabled]="busy()" (click)="accept(o)">Ignorer</button>
                    <button mat-button type="button" class="jc__keep" [disabled]="busy()" (click)="decline(o)">Garder</button>
                  </span>
                </div>
              }
              @for (r of rulesOf(c.hostId); track r.id) {
                <div class="jc__rule">
                  <span class="jc__rule-text">Ignoré — {{ kindLabel(r) }} : {{ r.sample }}</span>
                  <button mat-button type="button" class="jc__remove" [disabled]="busy()" (click)="remove(r)">Retirer</button>
                </div>
              }
            </li>
          } @empty {
            <li class="jc__hint">Aucun poste : appairez d'abord le runner du poste client.</li>
          }
        </ul>
        @if (settings(); as s) {
          <form class="jc__form" (submit)="$event.preventDefault(); save()">
            <h3 class="jc__sub">Rappel et alertes</h3>
            <mat-form-field appearance="outline" class="jc__field" subscriptSizing="dynamic">
              <mat-label>Heure du rappel</mat-label>
              <input matInput type="time" name="reminderTime" [(ngModel)]="s.reminderTime" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="jc__field" subscriptSizing="dynamic">
              <mat-label>Rappels (1 à 3)</mat-label>
              <input matInput type="number" min="1" max="3" name="reminderMax" [(ngModel)]="s.reminderMax" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="jc__field" subscriptSizing="dynamic">
              <mat-label>Minutes entre deux</mat-label>
              <input matInput type="number" min="15" max="180" name="every" [(ngModel)]="s.reminderEveryMinutes" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="jc__field" subscriptSizing="dynamic">
              <mat-label>Alerte avant réunion (min, 0 = non)</mat-label>
              <input matInput type="number" min="0" max="60" name="meeting" [(ngModel)]="s.meetingAlertMinutes" />
            </mat-form-field>
            <mat-slide-toggle class="jc__toggle" name="weekdays" [(ngModel)]="s.weekdaysOnly">Jours ouvrés seulement</mat-slide-toggle>
            <button mat-flat-button color="primary" type="submit" class="jc__save" [disabled]="busy()">Enregistrer</button>
          </form>
        }
        @if (error()) { <p class="jc__error" role="alert">{{ error() }}</p> }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jc {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(560px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jc__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jc__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jc__sub { margin: 0; flex: 1 1 100%; font-family: var(--cg-font-heading); font-size: 14px; }
    .jc__close, .jc__actions button, .jc__rule button { color: var(--cg-cream); }
    .jc__hint { margin: 0; font-size: 13px; opacity: .8; }
    .jc__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-2); }
    .jc__client { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-1) var(--cg-space-2);
      padding: var(--cg-space-2); border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 4px; }
    .jc__name { flex: 1 1 160px; font-weight: 600; font-size: 14px; }
    .jc__badge { font-size: 12px; padding: 0 var(--cg-space-1); border: 1px solid color-mix(in srgb, var(--cg-cream) 35%, transparent); border-radius: 4px; }
    .jc__badge--ko, .jc__error { color: var(--cg-orange-2); border-color: var(--cg-orange-2); }
    .jc__folder { flex: 1 1 100%; font-family: var(--cg-font-mono); font-size: 12px; }
    .jc__meta { flex: 1 1 100%; font-size: 12px; opacity: .7; }
    .jc__offer, .jc__rule { flex: 1 1 100%; display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-1);
      font-size: 13px; padding-top: var(--cg-space-1); border-top: 1px dashed color-mix(in srgb, var(--cg-cream) 18%, transparent); }
    .jc__offer-text, .jc__rule-text { flex: 1 1 200px; }
    .jc__actions { display: flex; flex-wrap: wrap; margin-left: auto; }
    .jc__error { margin: 0; font-size: 13px; }
    .jc__form { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jc__field { flex: 1 1 45%; min-width: 0; }
    @media (max-width: 767px) {
      .jc { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
      .jc__field { flex-basis: 100%; }
    }
  `],
})
export class JarvisClientMailComponent implements OnInit {
  private readonly client = inject(JarvisClientMailClient);
  private readonly snack = inject(MatSnackBar);

  readonly closed = output<void>();

  readonly clients = signal<ClientMailClient[]>([]);
  readonly settings = signal<ClientMailSettings | null>(null);
  readonly offers = signal<IgnoreOffer[]>([]);
  readonly rules = signal<IgnoreRule[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly pendingCount = computed(() => this.offers().length);

  ngOnInit(): void {
    this.reload();
  }

  kindLabel(x: { kind: 'SENDER' | 'SUBJECT' }): string {
    return ignoreKindLabel(x.kind);
  }

  offersOf(hostId: string): IgnoreOffer[] {
    return this.offers().filter((o) => o.hostId === hostId);
  }

  rulesOf(hostId: string): IgnoreRule[] {
    return this.rules().filter((r) => r.hostId === hostId);
  }

  reload(): void {
    this.client.overview().subscribe({
      next: (o) => {
        this.loading.set(false);
        this.clients.set(o.clients);
        this.settings.set({ ...o.settings });
        this.offers.set(o.offers ?? []);
        this.rules.set(o.rules ?? []);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Impossible de lire l’état des mails des clients.');
      },
    });
  }

  setActive(c: ClientMailClient, active: boolean): void {
    this.busy.set(true);
    this.client.setActive(c.hostId, active).subscribe({
      next: (fresh) => {
        this.busy.set(false);
        this.clients.update((list) => list.map((x) => (x.hostId === fresh.hostId ? fresh : x)));
      },
      error: () => {
        this.busy.set(false);
        this.snack.open('Réglage non enregistré.', 'OK', { duration: 4000 });
      },
    });
  }

  save(): void {
    const s = this.settings();
    if (!s) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.client.saveSettings({
      ...s, reminderMax: Number(s.reminderMax), reminderEveryMinutes: Number(s.reminderEveryMinutes),
      meetingAlertMinutes: Number(s.meetingAlertMinutes),
    }).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.settings.set({ ...saved });
        this.snack.open('Réglages enregistrés.', undefined, { duration: 2500 });
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(settingsError(err));
      },
    });
  }

  accept(o: IgnoreOffer): void {
    this.busy.set(true);
    this.client.accept(o.id).subscribe({
      next: (rule) => {
        this.busy.set(false);
        this.offers.update((list) => list.filter((x) => x.id !== o.id));
        this.rules.update((list) => [rule, ...list]);
        this.snack.open('Ces mails seront ignorés, sans IA.', undefined, { duration: 3000 });
      },
      error: () => {
        this.busy.set(false);
        this.reload();
      },
    });
  }

  decline(o: IgnoreOffer): void {
    this.busy.set(true);
    this.client.decline(o.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.offers.update((list) => list.filter((x) => x.id !== o.id));
      },
      error: () => {
        this.busy.set(false);
        this.reload();
      },
    });
  }

  remove(r: IgnoreRule): void {
    this.busy.set(true);
    this.client.removeRule(r.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.rules.update((list) => list.filter((x) => x.id !== r.id));
        this.snack.open('Règle retirée : ces mails repassent au tri.', undefined, { duration: 3000 });
      },
      error: () => {
        this.busy.set(false);
        this.reload();
      },
    });
  }
}
