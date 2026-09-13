import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable } from 'rxjs';

import { HostMailAddress } from '../../core/models/mail.models';
import { MailService } from '../../core/services/mail.service';
import { isPlausibleAddress, isValidCode, mailErrorOf } from './host-mail-address';

export interface HostMailAddressDialogData {
  hostId: string;
  hostName: string;
  view: HostMailAddress | null;
}

/**
 * **Régler l'adresse de réception** d'un client (F-110 / SF-110-01) : saisir l'adresse, recevoir un code à
 * 6 chiffres, le saisir. Tant que le code n'est pas saisi, rien n'est envoyé à cette adresse. Le dialogue se
 * referme sur le dernier état connu.
 */
@Component({
  selector: 'app-host-mail-address-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule],
  template: `
    <h2 mat-dialog-title>Adresse de réception de « {{ data.hostName }} »</h2>
    <mat-dialog-content class="mail">
      <p class="mail__lead">
        Les courriels que vous vous envoyez depuis ce client arrivent à cette adresse — jamais chez un tiers. Elle
        n'est utilisée qu'une fois vérifiée ; sinon, ils partent à l'adresse de votre compte
        @if (view()?.accountEmail; as account) { ({{ account }}) }.
      </p>

      @if (view()?.verified && !changing()) {
        <p class="mail__verified">Adresse vérifiée : <strong>{{ view()?.address }}</strong></p>
        <div class="mail__row">
          <button mat-stroked-button type="button" class="mail__change" [disabled]="busy()" (click)="changing.set(true)">
            Changer d'adresse
          </button>
          <button mat-button type="button" class="mail__remove" [disabled]="busy()" (click)="remove()">
            Retirer l'adresse
          </button>
        </div>
      } @else if (view()?.codePending && !changing()) {
        <p class="mail__sent">
          Code envoyé à <strong>{{ view()?.address }}</strong>, valable 15 minutes. Vérifiez vos courriers indésirables
          la première fois.
        </p>
        <mat-form-field appearance="outline" class="mail__code" subscriptSizing="dynamic">
          <mat-label>Code à 6 chiffres</mat-label>
          <input matInput class="mail__code-input" inputmode="numeric" autocomplete="one-time-code" maxlength="6"
            [value]="code()" (input)="code.set($any($event.target).value)" (keyup.enter)="verify()" />
          @if (code().length > 0 && !codeOk()) {
            <mat-error>Six chiffres.</mat-error>
          }
        </mat-form-field>
        <div class="mail__row">
          <button mat-flat-button color="primary" type="button" class="mail__verify" [disabled]="!codeOk() || busy()"
            (click)="verify()">
            Vérifier
          </button>
          <button mat-button type="button" class="mail__resend" [disabled]="busy()" (click)="resend()">
            Renvoyer le code
          </button>
          <button mat-button type="button" class="mail__other" [disabled]="busy()" (click)="changing.set(true)">
            Autre adresse
          </button>
        </div>
      } @else {
        <mat-form-field appearance="outline" class="mail__address" subscriptSizing="dynamic">
          <mat-label>Adresse de réception</mat-label>
          <input matInput type="email" class="mail__address-input" autocomplete="email" maxlength="254"
            [value]="address()" (input)="address.set($any($event.target).value)" (keyup.enter)="declare()" />
          @if (address().length > 0 && !addressOk()) {
            <mat-error>Adresse invalide : nom&#64;domaine.fr</mat-error>
          }
        </mat-form-field>
        <div class="mail__row">
          <button mat-flat-button color="primary" type="button" class="mail__declare" [disabled]="!addressOk() || busy()"
            (click)="declare()">
            Envoyer le code
          </button>
          @if (changing()) {
            <button mat-button type="button" class="mail__back" [disabled]="busy()" (click)="changing.set(false)">
              Revenir
            </button>
          }
        </div>
      }

      @if (busy()) {
        <mat-spinner class="mail__spinner" diameter="20"></mat-spinner>
      }
      @if (error(); as message) {
        <p class="mail__error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" class="mail__close" (click)="close()">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .mail__lead,
    .mail__sent,
    .mail__verified {
      margin: 0 0 var(--cg-space-3);
      color: var(--cg-text-secondary);
    }

    .mail__address,
    .mail__code {
      width: 100%;
      max-width: 360px;
    }

    .mail__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .mail__spinner {
      margin-top: var(--cg-space-2);
    }

    .mail__error {
      margin: var(--cg-space-2) 0 0;
      color: var(--cg-error);
    }
  `,
})
export class HostMailAddressDialogComponent {
  readonly data = inject<HostMailAddressDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<HostMailAddressDialogComponent, HostMailAddress | null>);
  private readonly mail = inject(MailService);

  static readonly DIALOG_WIDTH = '560px';

  readonly view = signal<HostMailAddress | null>(this.data.view);
  readonly changing = signal(false);
  readonly address = signal(this.data.view?.address ?? '');
  readonly code = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly addressOk = computed(() => isPlausibleAddress(this.address()));
  readonly codeOk = computed(() => isValidCode(this.code()));

  declare(): void {
    if (!this.addressOk() || this.busy()) {
      return;
    }
    this.run(this.mail.declare(this.data.hostId, this.address().trim()), () => this.changing.set(false));
  }

  verify(): void {
    if (!this.codeOk() || this.busy()) {
      return;
    }
    this.run(this.mail.verify(this.data.hostId, this.code().trim()), (view) => {
      if (view.verified) {
        this.dialogRef.close(view);
      }
    });
  }

  resend(): void {
    this.run(this.mail.resend(this.data.hostId), () => this.code.set(''));
  }

  remove(): void {
    this.run(this.mail.remove(this.data.hostId), () => this.address.set(''));
  }

  close(): void {
    this.dialogRef.close(this.view());
  }

  private run(call: Observable<HostMailAddress>, then: (view: HostMailAddress) => void): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (view) => {
        this.busy.set(false);
        this.view.set(view);
        then(view);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(mailErrorOf(err));
      },
    });
  }
}
