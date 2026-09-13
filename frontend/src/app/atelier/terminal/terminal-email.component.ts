import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Subscription, timer } from 'rxjs';

import { AtelierTerminalEmail } from '../../core/models/atelier.models';
import { MailService } from '../../core/services/mail.service';
import { emailFallbackNotice, emailHeadline, emailStateLabel, isFinalEmailStatus } from './terminal-email';

/** Période de relecture de l'état de remise, en millisecondes. */
export const EMAIL_POLL_MS = 3000;
/** Relectures au plus : au-delà, l'état reste celui lu (la reprise peut prendre une heure). */
export const EMAIL_POLL_MAX = 40;

/**
 * **Le bloc « Courriel envoyé »** d'un terminal (F-110 / SF-110-02) : à qui, quel objet, et l'état de remise —
 * relu tant que l'envoi n'est pas terminé. Le repli sur l'adresse du compte est dit, comme la quarantaine
 * possible d'une boîte d'entreprise, invisible d'ici.
 */
@Component({
  selector: 'app-terminal-email',
  imports: [MatIconModule],
  template: `
    <div class="terminal-email" [attr.data-status]="status()" role="status">
      <p class="terminal-email__line">
        <mat-icon class="terminal-email__icon" aria-hidden="true">{{ icon() }}</mat-icon>
        <span class="terminal-email__headline">{{ headline() }}</span>
        <span class="terminal-email__state">— {{ state() }}</span>
      </p>
      @if (fallback(); as notice) {
        <p class="terminal-email__note terminal-email__fallback">{{ notice }}</p>
      }
      <p class="terminal-email__note">Vérifiez vos courriers indésirables la première fois.</p>
    </div>
  `,
  styles: `
    .terminal-email {
      margin: var(--cg-space-1) 0;
      padding: var(--cg-space-2) var(--cg-space-3);
      border-left: 4px solid var(--cg-divider);
      font-size: 13px;
    }

    .terminal-email[data-status='SENT'] {
      border-left-color: var(--cg-success);
    }

    .terminal-email[data-status='FAILED'] {
      border-left-color: var(--cg-error);
    }

    .terminal-email__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      margin: 0;
      overflow-wrap: anywhere;
    }

    .terminal-email__icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .terminal-email__state {
      font-weight: 500;
    }

    .terminal-email[data-status='FAILED'] .terminal-email__state {
      color: var(--cg-error);
    }

    .terminal-email__note {
      margin: var(--cg-space-1) 0 0;
      opacity: 0.8;
    }
  `,
})
export class TerminalEmailComponent {
  private readonly mail = inject(MailService);

  readonly receipt = input.required<AtelierTerminalEmail>();

  readonly status = signal<string>('PENDING');
  readonly failureReason = signal<string | null>(null);

  readonly headline = computed(() => emailHeadline(this.receipt()));
  readonly fallback = computed(() => emailFallbackNotice(this.receipt()));
  readonly state = computed(() => emailStateLabel(this.status(), this.failureReason()));
  readonly icon = computed(() => (this.status() === 'SENT' ? 'mark_email_read'
    : this.status() === 'FAILED' ? 'error_outline' : 'forward_to_inbox'));

  private polling: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.polling?.unsubscribe());
    effect(() => {
      const receipt = this.receipt();
      untracked(() => this.follow(receipt));
    });
  }

  private follow(receipt: AtelierTerminalEmail): void {
    this.polling?.unsubscribe();
    this.status.set(receipt.status || 'PENDING');
    this.failureReason.set(null);
    if (!receipt.emailId) {
      return;
    }
    // Un reçu relu d'un historique porte l'état de sa mise en file : on relit toujours au moins une fois.
    let reads = 0;
    this.polling = timer(0, EMAIL_POLL_MS).subscribe(() => {
      reads += 1;
      this.mail.email(receipt.emailId).subscribe({
        next: (view) => {
          this.status.set(view.status);
          this.failureReason.set(view.failureReason);
          if (isFinalEmailStatus(view.status)) {
            this.polling?.unsubscribe();
          }
        },
        // Illisible (droit retiré, courriel purgé) : on garde ce qu'on sait et on cesse de relire.
        error: () => this.polling?.unsubscribe(),
      });
      if (reads >= EMAIL_POLL_MAX) {
        this.polling?.unsubscribe();
      }
    });
  }
}
