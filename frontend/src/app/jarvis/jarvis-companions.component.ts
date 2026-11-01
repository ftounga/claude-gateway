import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { JarvisCompanionDevice, JarvisCompanionPairingCode } from '../core/models/jarvis.models';
import { JarvisService } from '../core/services/jarvis.service';

/**
 * **Les annonces téléphone verrouillé** (F-232 / SF-232-03, D1b) — appairer le compagnon Android par un code (même
 * geste que l'appairage d'un poste), voir les téléphones appairés, en révoquer un.
 *
 * Discret : un seul bouton tant que le panneau est fermé, et seulement sur Android ou quand un téléphone est déjà
 * appairé. La révocation se confirme en ligne (pas de `window.confirm`).
 */
@Component({
  selector: 'app-jarvis-companions',
  imports: [DatePipe, MatButtonModule, MatIconModule],
  template: `
    @if (visible()) {
      <button mat-button type="button" class="jc__toggle" [attr.aria-expanded]="open()" (click)="open.set(!open())">
        <mat-icon aria-hidden="true">campaign</mat-icon>
        Annonces téléphone verrouillé
        @if (devices().length) { ({{ devices().length }}) }
      </button>
      @if (open()) {
        <section class="jc" aria-label="Annonces téléphone verrouillé">
          <p class="jc__hint">
            Le compagnon Android dit à voix haute la fin d'un tour lancé à la voix et les alertes de veille, même
            téléphone verrouillé. Saisis ce code dans le compagnon (étape « Les annonces »).
          </p>
          @if (code(); as pairing) {
            <p class="jc__code" role="status">
              <span class="jc__code-value">{{ pairing.code }}</span>
              <span class="jc__code-expiry">valable jusqu'à {{ pairing.expiresAt | date: 'HH:mm' }}</span>
            </p>
          }
          <button mat-stroked-button type="button" class="jc__pair" (click)="pair()" [disabled]="busy()">
            <mat-icon aria-hidden="true">phonelink_lock</mat-icon>
            {{ code() ? 'Nouveau code' : 'Appairer le compagnon' }}
          </button>
          @if (error(); as message) {
            <p class="jc__error" role="alert">{{ message }}</p>
          }
          @if (devices().length) {
            <ul class="jc__list">
              @for (d of devices(); track d.id) {
                <li class="jc__item">
                  <span class="jc__label">{{ d.label }}</span>
                  <span class="jc__seen">
                    {{ d.lastSeenAt ? 'vu ' + (d.lastSeenAt | date: 'dd/MM HH:mm') : 'jamais vu' }}
                  </span>
                  @if (confirming() === d.id) {
                    <span class="jc__confirm">Révoquer ?</span>
                    <button mat-button type="button" class="jc__yes" (click)="revoke(d)">Oui</button>
                    <button mat-button type="button" (click)="confirming.set(null)">Non</button>
                  } @else {
                    <button mat-button type="button" class="jc__revoke" (click)="confirming.set(d.id)">Révoquer</button>
                  }
                </li>
              }
            </ul>
          }
        </section>
      }
    }
  `,
  styles: [`
    :host { display: block; text-align: center; }
    .jc__toggle { color: var(--cg-cream); font-size: 13px; }
    .jc {
      margin: var(--cg-space-2) auto 0;
      max-width: 480px;
      padding: var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 12px;
      text-align: left;
      font-size: 14px;
    }
    .jc__hint { margin: 0 0 var(--cg-space-2); opacity: .85; }
    .jc__code { display: flex; align-items: baseline; gap: var(--cg-space-2); margin: 0 0 var(--cg-space-2); }
    .jc__code-value { font-family: var(--cg-font-mono, monospace); font-size: 24px; letter-spacing: .15em; color: var(--cg-accent); }
    .jc__code-expiry { font-size: 12px; opacity: .8; }
    .jc__pair { color: var(--cg-cream); }
    .jc__error { color: var(--cg-orange-2); margin: var(--cg-space-2) 0 0; }
    .jc__list { list-style: none; margin: var(--cg-space-3) 0 0; padding: 0; }
    .jc__item { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-1); padding: var(--cg-space-1) 0; border-top: 1px solid var(--cg-divider); }
    .jc__label { flex: 1 1 auto; }
    .jc__seen, .jc__confirm { font-size: 12px; opacity: .8; }
    .jc__item button { color: var(--cg-cream); }
  `],
})
export class JarvisCompanionsComponent implements OnInit {
  private readonly jarvis = inject(JarvisService);

  /** Sur Android, le panneau est proposé même sans téléphone appairé. */
  readonly android = input(false);

  readonly devices = signal<JarvisCompanionDevice[]>([]);
  readonly code = signal<JarvisCompanionPairingCode | null>(null);
  readonly open = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly confirming = signal<string | null>(null);
  readonly visible = computed(() => this.android() || this.devices().length > 0);

  ngOnInit(): void {
    this.load();
  }

  pair(): void {
    this.busy.set(true);
    this.error.set(null);
    this.jarvis.companionPairingCode().subscribe({
      next: (code) => {
        this.code.set(code);
        this.busy.set(false);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(err?.status === 409
          ? 'Déjà cinq téléphones appairés : révoque d’abord un ancien.'
          : 'Le code n’a pas pu être créé. Réessaie.');
      },
    });
  }

  revoke(device: JarvisCompanionDevice): void {
    this.confirming.set(null);
    this.jarvis.revokeCompanion(device.id).subscribe({
      next: () => this.devices.set(this.devices().filter((d) => d.id !== device.id)),
      error: () => this.error.set('La révocation a échoué. Réessaie.'),
    });
  }

  private load(): void {
    this.jarvis.companions().subscribe({
      next: (list) => this.devices.set(list),
      error: () => this.devices.set([]),
    });
  }
}
