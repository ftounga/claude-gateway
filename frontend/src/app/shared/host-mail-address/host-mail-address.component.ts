import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { HostMailAddress } from '../../core/models/mail.models';
import { MailService } from '../../core/services/mail.service';
import {
  HostMailAddressDialogComponent,
  HostMailAddressDialogData,
} from './host-mail-address-dialog.component';
import { mailSentence } from './host-mail-address';

/**
 * **Les courriels du client, dans son en-tête** (F-110 / SF-110-01), Forge et Vigie : à quelle adresse ils
 * arrivent — l'adresse vérifiée du client, ou, dit en clair, celle du compte — et *Régler*.
 *
 * <p>Silencieuse quand elle ne peut pas lire l'état : l'en-tête résume, il ne diagnostique pas.</p>
 */
@Component({
  selector: 'app-host-mail-address',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (view(); as current) {
      <span class="host-mail" [class.host-mail--attention]="sentence()?.attention">
        <mat-icon class="host-mail__icon" aria-hidden="true">mail</mat-icon>
        <span class="host-mail__text">{{ sentence()?.text }}</span>
        <button
          mat-button
          type="button"
          class="host-mail__edit"
          (click)="edit()"
          [attr.aria-label]="'Régler l’adresse de réception de ' + hostName()"
          matTooltip="L'adresse où arrivent les courriels que vous vous envoyez pour ce client"
        >
          <mat-icon>alternate_email</mat-icon>
          Régler
        </button>
      </span>
    }
  `,
  styles: `
    .host-mail {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      font-size: 13px;
      color: var(--cg-text-secondary);
      overflow-wrap: anywhere;
    }

    /* §12 : un code en attente s'écrit en ambre, jamais en aplat. */
    .host-mail--attention .host-mail__text {
      color: #F9A825;
      font-weight: 500;
    }

    .host-mail__icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .host-mail__edit {
      min-width: 0;
    }
  `,
})
export class HostMailAddressComponent {
  private readonly mail = inject(MailService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly hostId = input.required<string>();
  readonly hostName = input('');

  readonly view = signal<HostMailAddress | null>(null);
  readonly sentence = computed(() => {
    const current = this.view();
    return current ? mailSentence(current) : null;
  });

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      untracked(() => this.load(hostId));
    });
  }

  edit(): void {
    const hostId = this.hostId();
    const before = this.view();
    this.dialog
      .open<HostMailAddressDialogComponent, HostMailAddressDialogData, HostMailAddress | null>(
        HostMailAddressDialogComponent,
        {
          data: { hostId, hostName: this.hostName(), view: before },
          width: HostMailAddressDialogComponent.DIALOG_WIDTH,
          maxWidth: '95vw',
          autoFocus: false,
        },
      )
      .afterClosed()
      .subscribe((after) => {
        if (!after || hostId !== this.hostId()) {
          return;
        }
        this.view.set(after);
        if (after.verified && after.address && !(before?.verified && before.address === after.address)) {
          this.snackBar.open(`Adresse vérifiée : les courriels de ${this.hostName()} arriveront à ${after.address}.`,
            'Fermer', { duration: 5000 });
        }
      });
  }

  private load(hostId: string): void {
    this.view.set(null);
    this.mail.address(hostId).subscribe({
      next: (view) => {
        if (hostId === this.hostId()) {
          this.view.set(view);
        }
      },
      error: () => undefined,
    });
  }
}
