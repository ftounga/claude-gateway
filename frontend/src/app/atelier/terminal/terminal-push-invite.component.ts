import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { PushActivationResult, PushActivationService } from '../../core/services/push-activation.service';

/** Clé de l'appareil : « Ne plus proposer ». */
export const PUSH_INVITE_NEVER_KEY = 'cg.pushInvite.never';

/** Un tour qui dure autant mérite qu'on rappelle l'invitation mise en sommeil. */
export const LONG_TURN_MS = 20_000;

/** Durée d'affichage de la confirmation « actives sur cet appareil ». */
export const CONFIRMATION_MS = 6_000;

/** Ce que le bandeau dit après « Activer », quand ce n'est pas un succès. */
export function activationMessage(result: PushActivationResult): string {
  switch (result) {
    case 'enabled':
      return 'Notifications actives sur cet appareil.';
    case 'denied':
      return 'Le navigateur bloque les notifications de ce site : autorisez-les dans les réglages du site, '
        + 'puis réessayez depuis Paramètres > Notifications.';
    case 'not-configured':
      return 'Les notifications ne sont pas configurées sur le serveur.';
    case 'unsupported':
      return 'Les notifications ne sont pas disponibles sur cet appareil.';
  }
}

function readNever(): boolean {
  try {
    return localStorage.getItem(PUSH_INVITE_NEVER_KEY) === '1';
  } catch {
    return false;
  }
}

function writeNever(): void {
  try {
    localStorage.setItem(PUSH_INVITE_NEVER_KEY, '1');
  } catch {
    // Navigation privée : le choix vaut pour cet onglet seulement.
  }
}

/**
 * **L'invitation à activer les notifications** (F-185 / SF-185-01) : là où on les attend, dans le
 * terminal, au-dessus de la saisie. Le Web Push existait depuis F-153 mais son activation était
 * enfouie dans les paramètres — aucun appareil abonné en prod, donc « Une réponse est prête »
 * n'arrivait nulle part.
 *
 * <p>Affichée si le push est supporté, l'appareil non abonné, la permission non refusée et
 * l'invitation non écartée. « Plus tard » la met en sommeil pour l'onglet ; elle revient
 * <b>une fois</b> quand un tour dure {@link LONG_TURN_MS}, le moment où l'on voudrait être prévenu.</p>
 */
@Component({
  selector: 'app-terminal-push-invite',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (message(); as text) {
      <div class="push-invite push-invite--result" role="status">
        <mat-icon class="push-invite__icon" aria-hidden="true">{{ succeeded() ? 'notifications_active' : 'notifications_off' }}</mat-icon>
        <span class="push-invite__text">{{ text }}</span>
        @if (!succeeded()) {
          <button mat-button type="button" class="push-invite__close" (click)="closeResult()">Fermer</button>
        }
      </div>
    } @else if (visible()) {
      <div class="push-invite" role="region" aria-label="Activer les notifications">
        <mat-icon class="push-invite__icon" aria-hidden="true">notifications</mat-icon>
        <span class="push-invite__text">{{ reminded() ? reminderText : inviteText }}</span>
        <span class="push-invite__actions">
          <button mat-flat-button type="button" class="push-invite__enable" [disabled]="busy()" (click)="activate()">Activer</button>
          <button mat-button type="button" class="push-invite__later" (click)="later()">Plus tard</button>
          <button mat-button type="button" class="push-invite__never" (click)="never()">Ne plus proposer</button>
        </span>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .push-invite {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1) var(--cg-space-2);
      box-sizing: border-box;
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      font-size: 13px;
    }

    .push-invite__icon {
      flex: none;
      font-size: 18px;
      width: 18px;
      height: 18px;
      color: var(--cg-orange-2);
    }

    .push-invite__text {
      flex: 1 1 220px;
      min-width: 0;
    }

    .push-invite__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1);
    }
  `,
})
export class TerminalPushInviteComponent {
  private readonly push = inject(PushActivationService);

  /** Vrai pendant un tour : un tour long rappelle l'invitation mise en sommeil. */
  readonly running = input(false);

  protected readonly inviteText =
    "Soyez prévenu quand une réponse est prête ou qu'une décision vous attend, même l'application fermée.";
  protected readonly reminderText =
    'Ce travail prend du temps : soyez prévenu quand la réponse arrive.';

  private readonly neverAgain = signal(readNever());
  private readonly asleep = signal(false);
  /** Le rappel du tour long a-t-il déjà servi ? Une seule fois par onglet. */
  private reminderUsed = false;
  protected readonly reminded = signal(false);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly succeeded = signal(false);
  /** Après un échec (refus, non configuré), on ne repropose plus dans cet onglet. */
  private readonly failed = signal(false);

  readonly visible = computed(() =>
    this.push.supported
      && !this.push.enabled()
      && this.push.permission() !== 'denied'
      && !this.neverAgain()
      && !this.asleep()
      && !this.failed());

  private longTurnTimer: ReturnType<typeof setTimeout> | null = null;
  private confirmationTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const running = this.running();
      this.clearLongTurnTimer();
      if (running && !this.reminderUsed) {
        this.longTurnTimer = setTimeout(() => this.remind(), LONG_TURN_MS);
      }
    });
    inject(DestroyRef).onDestroy(() => {
      this.clearLongTurnTimer();
      if (this.confirmationTimer) {
        clearTimeout(this.confirmationTimer);
      }
    });
  }

  protected async activate(): Promise<void> {
    this.busy.set(true);
    try {
      const result = await this.push.enable();
      this.succeeded.set(result === 'enabled');
      this.message.set(activationMessage(result));
      if (result === 'enabled') {
        this.confirmationTimer = setTimeout(() => this.message.set(null), CONFIRMATION_MS);
      } else {
        this.failed.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected later(): void {
    this.asleep.set(true);
    this.reminded.set(false);
  }

  protected never(): void {
    writeNever();
    this.neverAgain.set(true);
  }

  protected closeResult(): void {
    this.message.set(null);
  }

  /** Un tour long : on réveille l'invitation en sommeil, une seule fois. */
  private remind(): void {
    this.longTurnTimer = null;
    if (!this.asleep()) {
      return;
    }
    this.reminderUsed = true;
    this.reminded.set(true);
    this.asleep.set(false);
  }

  private clearLongTurnTimer(): void {
    if (this.longTurnTimer) {
      clearTimeout(this.longTurnTimer);
      this.longTurnTimer = null;
    }
  }
}
