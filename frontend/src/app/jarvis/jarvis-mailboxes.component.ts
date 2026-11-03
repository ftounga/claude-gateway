import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import {
  GOOGLE_APP_PASSWORDS_URL, JarvisMailClient, MAX_MAIL_ACCOUNTS, MailAccount, MailError, mailErrorMessage,
  mailStatusLabel,
} from './jarvis-mail';

/**
 * **Le volet « Mes boîtes »** (F-242 / SF-242-01) — dans les réglages de `/jarvis`.
 *
 * Un pas-à-pas en trois lignes (validation en deux étapes, mot de passe d'application, le coller), le formulaire
 * adresse / libellé / mot de passe (champ masqué), et la liste des boîtes reliées avec leur état, `…last4`,
 * « Tester » et « Révoquer » (confirmation, puis invitation à révoquer aussi chez Google). Le parent ne l'affiche
 * **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-mailboxes',
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <section class="jm" aria-label="Mes boîtes">
      <header class="jm__head">
        <h2 class="jm__title">Mes boîtes</h2>
        <button mat-icon-button type="button" class="jm__close" aria-label="Fermer mes boîtes" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      <p class="jm__hint">
        Jarvis lit et trie vos mails, prépare vos réponses, et n'envoie ni ne supprime rien sans votre « oui ».
        Jamais en présentation.
      </p>
      @if (loading()) {
        <p class="jm__hint">Chargement…</p>
      } @else {
        <ul class="jm__list">
          @for (a of accounts(); track a.id) {
            <li class="jm__account">
              <span class="jm__address">{{ a.label }} — {{ a.address }}</span>
              <span class="jm__status" [class.jm__status--ko]="a.status !== 'OK'">{{ statusLabel(a) }}</span>
              <span class="jm__meta">…{{ a.secretLast4 }}@if (a.lastOkAt) { · vue le {{ a.lastOkAt | date: 'dd/MM/yyyy HH:mm' }} }</span>
              @if (a.lastError && a.status !== 'OK') { <span class="jm__error" role="alert">{{ a.lastError }}</span> }
              <span class="jm__actions">
                <button mat-button type="button" class="jm__test" [disabled]="busy()" (click)="test(a)">Tester</button>
                <button mat-button type="button" color="warn" class="jm__revoke" [disabled]="busy()" (click)="revoke(a)">Révoquer</button>
              </span>
            </li>
          }
        </ul>
        @if (canAdd()) {
          <ol class="jm__steps">
            <li>Activez la validation en deux étapes de votre compte Google.</li>
            <li>Créez un mot de passe d'application sur
              <a class="jm__link" [href]="appPasswordsUrl" target="_blank" rel="noopener noreferrer">myaccount.google.com/apppasswords</a>.</li>
            <li>Collez-le ici : Jarvis le teste, puis le garde chiffré.</li>
          </ol>
          <form class="jm__form" (submit)="$event.preventDefault(); link(address.value, label.value, password)">
            <mat-form-field appearance="outline" class="jm__field" subscriptSizing="dynamic">
              <mat-label>Adresse</mat-label>
              <input matInput #address type="email" autocomplete="email" maxlength="254" name="address" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="jm__field jm__field--label" subscriptSizing="dynamic">
              <mat-label>Libellé</mat-label>
              <input matInput #label maxlength="40" name="label" placeholder="Perso, Pro…" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="jm__field" subscriptSizing="dynamic">
              <mat-label>Mot de passe d'application</mat-label>
              <input matInput #password type="password" autocomplete="off" maxlength="64" name="password" class="jm__password" />
            </mat-form-field>
            <button mat-flat-button color="primary" type="submit" class="jm__link-button" [disabled]="busy()">
              {{ busy() ? 'Test en cours…' : 'Relier' }}
            </button>
          </form>
        } @else {
          <p class="jm__hint">{{ max }} boîtes au plus : révoquez-en une pour en relier une autre.</p>
        }
        @if (error()) { <p class="jm__error" role="alert">{{ error() }}</p> }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jm {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(520px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jm__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jm__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jm__close, .jm__actions button { color: var(--cg-cream); }
    .jm__hint { margin: 0; font-size: 13px; opacity: .8; }
    .jm__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-2); }
    .jm__account { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-1) var(--cg-space-2);
      padding: var(--cg-space-2); border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 4px; }
    .jm__address { flex: 1 1 220px; font-weight: 600; font-size: 14px; }
    .jm__status { font-size: 12px; padding: 0 var(--cg-space-1); border: 1px solid color-mix(in srgb, var(--cg-cream) 35%, transparent); border-radius: 4px; }
    .jm__status--ko, .jm__error { color: var(--cg-orange-2); border-color: var(--cg-orange-2); }
    .jm__meta { font-family: var(--cg-font-mono); font-size: 12px; opacity: .7; }
    .jm__error { margin: 0; font-size: 13px; }
    .jm__actions { display: flex; flex-wrap: wrap; margin-left: auto; }
    .jm__steps { margin: 0; padding-left: var(--cg-space-4); font-size: 13px; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .jm__link { color: var(--cg-orange-2); }
    .jm__form { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jm__field { flex: 1 1 100%; min-width: 0; }
    .jm__field--label { flex-basis: 40%; }
    @media (max-width: 767px) {
      .jm { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisMailboxesComponent implements OnInit {
  private readonly client = inject(JarvisMailClient);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);

  readonly closed = output<void>();

  readonly max = MAX_MAIL_ACCOUNTS;
  readonly appPasswordsUrl = GOOGLE_APP_PASSWORDS_URL;
  readonly accounts = signal<MailAccount[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly canAdd = computed(() => this.accounts().length < MAX_MAIL_ACCOUNTS);

  ngOnInit(): void {
    this.reload();
  }

  statusLabel(a: MailAccount): string {
    return mailStatusLabel(a.status);
  }

  reload(): void {
    this.client.list().subscribe({
      next: (list) => {
        this.loading.set(false);
        this.accounts.set(list);
      },
      error: (err: MailError) => {
        this.loading.set(false);
        this.error.set(mailErrorMessage(err));
      },
    });
  }

  /** « Relier » : la passerelle teste IMAP et SMTP avant d'écrire ; le champ du mot de passe est vidé quoi qu'il arrive. */
  link(address: string, label: string, password: HTMLInputElement): void {
    const secret = password.value;
    if (!address.trim() || !secret.trim()) {
      this.error.set('L’adresse et le mot de passe d’application sont requis.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.client.link(address.trim(), label.trim(), secret).subscribe({
      next: () => {
        password.value = '';
        this.busy.set(false);
        this.snack.open('Boîte reliée.', undefined, { duration: 2500 });
        this.reload();
      },
      error: (err: MailError) => {
        password.value = '';
        this.busy.set(false);
        this.error.set(mailErrorMessage(err));
      },
    });
  }

  test(a: MailAccount): void {
    this.busy.set(true);
    this.client.test(a.id).subscribe({
      next: (fresh) => {
        this.busy.set(false);
        this.accounts.update((list) => list.map((x) => (x.id === fresh.id ? fresh : x)));
        this.snack.open(fresh.status === 'OK' ? 'La boîte répond.' : (fresh.lastError ?? 'La boîte ne répond pas.'),
          'OK', { duration: 4000 });
      },
      error: (err: MailError) => {
        this.busy.set(false);
        this.snack.open(mailErrorMessage(err), 'OK', { duration: 6000 });
      },
    });
  }

  revoke(a: MailAccount): void {
    const data: ConfirmDialogData = {
      title: 'Révoquer cette boîte ?',
      message: `Jarvis oubliera ${a.address} et les mails gardés en cache. Pensez à révoquer aussi le mot de passe `
        + `d’application chez Google (myaccount.google.com/apppasswords).`,
      confirmLabel: 'Révoquer',
    };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) {
        return;
      }
      this.client.revoke(a.id).subscribe({
        next: () => {
          this.accounts.update((list) => list.filter((x) => x.id !== a.id));
          this.snack.open('Boîte révoquée. Révoquez aussi le mot de passe chez Google.', 'Ouvrir', { duration: 8000 })
            .onAction().subscribe(() => window.open(GOOGLE_APP_PASSWORDS_URL, '_blank', 'noopener'));
        },
        error: (err: MailError) => this.snack.open(mailErrorMessage(err), 'OK', { duration: 6000 }),
      });
    });
  }
}
