import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  MorningPoint, TerminalActionBoard, isMorningEmpty, morningSummary,
} from '../../core/models/terminal-actions.models';
import { PUSH_OPT_OUT_KEY, PushActivationService } from '../../core/services/push-activation.service';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

/** La clé locale « point du matin masqué » d'un terminal pour un jour. */
export function morningDismissKey(workspaceId: string, day: string): string {
  return `cg.morning.hidden.${workspaceId}.${day}`;
}

/** Le jour local `aaaa-mm-jj`. */
export function localDay(now = new Date()): string {
  const mm = String(now.getMonth() + 1).padStart(2, '0');
  const dd = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${mm}-${dd}`;
}

function readHidden(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

function writeHidden(key: string): void {
  try {
    localStorage.setItem(key, '1');
  } catch {
    // Mémoire locale indisponible : la ligne est masquée pour cette vue seulement.
  }
}

/**
 * **Le point du matin** (F-180 / SF-180-06, décision D6) : au-dessus de la bande des attentes, ce qui
 * appelle un geste aujourd'hui — relances dues, échéances, accès qui arrivent à terme. [Voir] ouvre le
 * panneau ; [×] la masque **pour la journée** sur ce terminal. Si l'appareil n'est pas abonné au push (et
 * que l'utilisateur ne l'a pas refusé), une invitation [Activer les notifications] — un geste explicite.
 *
 * <p>Relue à chaque changement du tableau des attentes (fin de tour, geste dans le panneau).</p>
 */
@Component({
  selector: 'app-terminal-morning-band',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (visible()) {
      <div class="morning" role="status" [attr.aria-label]="'Point du matin : ' + summary()">
        <mat-icon class="morning__icon" aria-hidden="true">wb_sunny</mat-icon>
        <span class="morning__label">Point du matin</span>
        <span class="morning__summary">{{ summary() }}</span>
        @if (canInvitePush()) {
          <button mat-button type="button" class="morning__push" (click)="enablePush()">
            <mat-icon>notifications_active</mat-icon>Activer les notifications
          </button>
        }
        <button mat-button type="button" class="morning__open" (click)="open.emit()">Voir</button>
        <button mat-icon-button type="button" class="morning__hide" matTooltip="Masquer pour aujourd'hui"
          aria-label="Masquer le point du matin pour aujourd'hui" (click)="hide()">
          <mat-icon>close</mat-icon>
        </button>
      </div>
    }
  `,
  styles: `
    :host { display: block; }

    .morning {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-1) var(--cg-space-1) var(--cg-space-3);
      border: 1px dashed var(--cg-orange-2);
      border-radius: 8px;
      color: var(--cg-orange-2);
      font-size: 13px;
      --mdc-text-button-label-text-color: var(--cg-orange-2);
      --mdc-icon-button-icon-color: var(--cg-orange-2);
    }

    .morning__icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
    }

    .morning__label {
      font-weight: 600;
    }

    .morning__summary {
      flex: 1;
      min-width: 0;
    }
  `,
})
export class TerminalMorningBandComponent {
  /** Le terminal courant. */
  readonly workspaceId = input<string | null>(null);
  /** Le tableau des attentes : quand il change, le point du matin se relit. */
  readonly board = input<TerminalActionBoard | null>(null);

  /** Ouvrir le panneau des attentes. */
  readonly open = output<void>();

  private readonly service = inject(TerminalActionsService);
  private readonly push = inject(PushActivationService, { optional: true });
  private readonly snackBar = inject(MatSnackBar);

  readonly point = signal<MorningPoint | null>(null);
  readonly hidden = signal(false);

  readonly summary = computed(() => morningSummary(this.point()));
  readonly visible = computed(() => !this.hidden() && !isMorningEmpty(this.point()));

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      this.board(); // relire quand le tableau change
      if (!id) {
        this.point.set(null);
        return;
      }
      this.hidden.set(readHidden(morningDismissKey(id, localDay())));
      this.service.morning(id).subscribe({
        next: p => this.point.set(p ?? null),
        error: () => this.point.set(null), // enrichissement : un échec n'affiche rien
      });
    });
  }

  /** L'invitation n'apparaît que si le push est possible, inactif, et ni refusé ni désactivé ici. */
  canInvitePush(): boolean {
    const push = this.push;
    if (!push || !push.supported || push.enabled()) {
      return false;
    }
    try {
      if (localStorage.getItem(PUSH_OPT_OUT_KEY) === '1') {
        return false;
      }
    } catch {
      // mémoire indisponible : on s'en tient à l'état du navigateur
    }
    return push.permission() !== 'denied';
  }

  async enablePush(): Promise<void> {
    if (!this.push) {
      return;
    }
    const result = await this.push.enable();
    const messages: Record<string, string> = {
      enabled: 'Notifications activées : le point du matin vous parviendra aussi hors de l’application.',
      denied: 'Le navigateur a refusé les notifications.',
      unsupported: 'Cet appareil ne permet pas les notifications.',
      'not-configured': 'Les notifications ne sont pas configurées sur ce service.',
    };
    this.snackBar.open(messages[result] ?? 'Notifications non activées.', 'Fermer', { duration: 5000 });
  }

  /** Masque la ligne pour aujourd'hui, sur ce terminal. */
  hide(): void {
    const id = this.workspaceId();
    if (id) {
      writeHidden(morningDismissKey(id, localDay()));
    }
    this.hidden.set(true);
  }
}
