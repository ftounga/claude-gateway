import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import { JarvisCompanionDevice, JarvisCompanionPairingCode } from '../core/models/jarvis.models';
import { JARVIS_COMPANION_DOWNLOAD_PATH, JarvisService } from '../core/services/jarvis.service';

/**
 * **Le compagnon Android** (F-232 / SF-232-03, D1c) — encart repliable de l'écran Jarvis : le parcours
 * d'installation, l'appairage par code (le même geste que l'appairage d'un poste), la liste des téléphones
 * appairés et leur révocation. Le jeton du téléphone n'ouvre que la réception des annonces Jarvis.
 */
@Component({
  selector: 'app-jarvis-companion-panel',
  imports: [DatePipe, MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="jcp" aria-label="Compagnon Android">
      <button mat-button type="button" class="jcp__toggle" (click)="toggle()" [attr.aria-expanded]="open()">
        <mat-icon aria-hidden="true">phone_android</mat-icon>
        Compagnon Android@if (devices().length) { · {{ devices().length }} appairé{{ devices().length > 1 ? 's' : '' }} }
      </button>
      @if (open()) {
        <div class="jcp__body">
          <ol class="jcp__steps">
            <li>
              @if (downloadable()) {
                <a [href]="downloadHref" download="jarvis-compagnon.apk">Télécharger le compagnon</a> sur le téléphone
                (Chrome, puis « Autoriser cette source »).
              } @else {
                Le compagnon n'est pas encore servi par cette passerelle.
              }
            </li>
            <li>L'ouvrir : autoriser le micro, les notifications, « ne pas couper pour la batterie » et l'ouverture plein écran.</li>
            <li>Apprendre « Jarvis » (trois fois).</li>
            <li>Appairer avec le code ci-dessous, puis démarrer l'écoute.</li>
          </ol>

          @if (code(); as shown) {
            <p class="jcp__code" aria-live="polite">
              <span class="jcp__code-value">{{ shown.code }}</span>
              <span class="jcp__muted">à taper dans le compagnon avant {{ shown.expiresAt | date: 'HH:mm' }}</span>
            </p>
          }
          <button mat-stroked-button type="button" class="jcp__pair" (click)="pair()" [disabled]="busy()">
            <mat-icon aria-hidden="true">link</mat-icon> Appairer un téléphone
          </button>
          @if (error(); as message) {
            <p class="jcp__error" role="alert">{{ message }}</p>
          }

          @if (devices().length) {
            <ul class="jcp__devices">
              @for (device of devices(); track device.id) {
                <li class="jcp__device">
                  <span>
                    {{ device.name }}
                    <span class="jcp__muted">
                      · appairé le {{ device.pairedAt | date: 'dd/MM' }}
                      @if (device.lastSeenAt) { · vu {{ device.lastSeenAt | date: 'dd/MM HH:mm' }} }
                    </span>
                  </span>
                  <button mat-button color="warn" type="button" (click)="revoke(device)">Révoquer</button>
                </li>
              }
            </ul>
          }
        </div>
      }
    </section>
  `,
  styles: `
    .jcp { width: 100%; max-width: 560px; color: var(--cg-cream); font-size: 13px; }
    .jcp__toggle { color: var(--cg-cream); }
    .jcp__body { display: flex; flex-direction: column; gap: var(--cg-space-2); padding: var(--cg-space-2) 0; }
    .jcp__steps { margin: 0; padding-left: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .jcp__steps a { color: var(--cg-orange-2); }
    .jcp__code { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-2); margin: 0; }
    .jcp__code-value { font-family: var(--cg-font-heading); font-size: 24px; letter-spacing: 0.15em; }
    .jcp__muted { opacity: 0.75; }
    .jcp__pair { color: var(--cg-cream); align-self: flex-start; }
    .jcp__error { margin: 0; color: var(--cg-orange-2); }
    .jcp__devices { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--cg-divider); }
    .jcp__device { display: flex; justify-content: space-between; align-items: center; gap: var(--cg-space-2);
      padding: var(--cg-space-1) 0; border-bottom: 1px solid var(--cg-divider); }
  `,
})
export class JarvisCompanionPanelComponent {
  private readonly jarvis = inject(JarvisService);
  private readonly dialog = inject(MatDialog);

  /** Vrai si la passerelle sert l'APK (route publique `/version`). */
  readonly downloadable = input(false);
  readonly downloadHref = JARVIS_COMPANION_DOWNLOAD_PATH;

  readonly open = signal(false);
  readonly busy = signal(false);
  readonly code = signal<JarvisCompanionPairingCode | null>(null);
  readonly devices = signal<JarvisCompanionDevice[]>([]);
  readonly error = signal<string | null>(null);

  toggle(): void {
    this.open.update((v) => !v);
    if (this.open()) {
      this.load();
    }
  }

  load(): void {
    this.jarvis.companionDevices().subscribe({
      next: (list) => this.devices.set(list),
      error: () => this.devices.set([]),
    });
  }

  pair(): void {
    this.busy.set(true);
    this.error.set(null);
    this.jarvis.companionPairingCode().subscribe({
      next: (code) => {
        this.code.set(code);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.code.set(null);
        this.error.set(e.status === 409
          ? 'Cinq téléphones sont déjà appairés : révoquez-en un d’abord.'
          : 'Impossible d’obtenir un code pour le moment.');
      },
    });
  }

  revoke(device: JarvisCompanionDevice): void {
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data: {
          title: 'Révoquer ce téléphone ?',
          message: `« ${device.name} » ne recevra plus les annonces de Jarvis. Il faudra l'appairer à nouveau.`,
          confirmLabel: 'Révoquer',
        },
        width: '480px', maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.jarvis.revokeCompanionDevice(device.id).subscribe({
          next: () => this.devices.update((list) => list.filter((d) => d.id !== device.id)),
          error: () => this.error.set('La révocation a échoué : réessayez.'),
        });
      });
  }
}
