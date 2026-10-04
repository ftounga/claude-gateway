import { Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierTerminalAttente, AtelierTerminalBlock } from '../../core/models/atelier.models';
import { TerminalAction } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

/**
 * **Le bloc de transcription d'une carte d'attente reçue au fil de l'eau** (F-175 / SF-175-05). Rangé
 * comme une carte (`withCards`) pour survivre au recalcul des blocs vivants.
 */
export function attenteBlock(toolUseId: string, attente: AtelierTerminalAttente): AtelierTerminalBlock {
  return {
    tool: 'attente',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    attente,
  };
}

/** « le 30/09 » à partir d'une date ISO ; vide si illisible. */
function day(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) {
    return '';
  }
  const dd = String(at.getDate()).padStart(2, '0');
  const mm = String(at.getMonth() + 1).padStart(2, '0');
  return `le ${dd}/${mm}`;
}

/** Le titre de la carte, selon ce qui s'est passé. */
export function attenteHeadline(a: AtelierTerminalAttente): string {
  switch (a.kind) {
    case 'ADDED':
      return 'Ajouté à tes attentes';
    case 'REQUESTED':
      return 'Marqué demandé';
    case 'PROPOSED':
      return a.proposedStatus === 'ANNULE' ? 'Je pense que c\'est sans objet' : 'Je pense que c\'est réglé';
    case 'ALREADY':
      if (a.status === 'DEMANDE') {
        return 'Déjà demandé';
      }
      return a.match === 'MEANING' ? 'Une attente dit déjà la même chose' : 'Déjà dans tes attentes';
    default:
      return 'Attente';
  }
}

/** La ligne de détail : « demandé à Zahi le 30/09 par Teams ». */
export function attenteDetail(a: AtelierTerminalAttente): string {
  if (a.status === 'DEMANDE') {
    return ['demandé', a.requestedTo ? `à ${a.requestedTo}` : '', day(a.requestedAt),
      a.channel ? `par ${a.channel}` : ''].filter(Boolean).join(' ');
  }
  if (a.kind === 'ALREADY' && a.match === 'KEY_ON_HOST') {
    return 'née dans un autre terminal du poste';
  }
  return '';
}

/**
 * **La carte d'une attente dans le fil** (F-175 / SF-175-05, décision D7) : à l'inscription, à la
 * reconnaissance d'un doublon, au passage à « Demandé », et à la **proposition de fermeture**
 * [Confirmer] [Pas encore]. Sans réponse, l'attente reste ouverte : rien ne sort sans le geste.
 *
 * <p>La carte est un **instantané** rangé dans la transcription ; l'état vivant vient du tableau des
 * attentes (`live`). Une proposition déjà confirmée ou écartée n'offre plus ses boutons — la carte
 * ne ment pas sur un geste déjà fait.</p>
 */
@Component({
  selector: 'app-attente-card',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="attente-card" [class.attente-card--proposal]="attente().kind === 'PROPOSED'" role="group"
      [attr.aria-label]="headline()">
      <mat-icon class="attente-card__icon" aria-hidden="true">{{ icon() }}</mat-icon>
      <div class="attente-card__body">
        <p class="attente-card__headline">{{ headline() }}</p>
        <p class="attente-card__what">« {{ attente().description }} »</p>
        @if (detail()) {
          <p class="attente-card__detail">{{ detail() }}</p>
        }
        @if (attente().kind === 'PROPOSED' && attente().proposedReason) {
          <p class="attente-card__detail">Tu as dit : « {{ attente().proposedReason }} »</p>
        }
        @if (attente().kind === 'PROPOSED') {
          @if (outcome()) {
            <p class="attente-card__settled">{{ outcome() }}</p>
          } @else if (pending() && !readOnly()) {
            <div class="attente-card__gestures">
              <button mat-flat-button type="button" class="attente-card__confirm" [disabled]="busy()"
                (click)="confirm()">Confirmer</button>
              <button mat-button type="button" class="attente-card__dismiss" [disabled]="busy()"
                (click)="dismiss()">Pas encore</button>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .attente-card {
      display: flex;
      gap: var(--cg-space-2);
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-orange-2);
      border-radius: 8px;
      color: inherit;
      font-family: var(--cg-font-body);
    }

    .attente-card--proposal {
      border-left-width: 4px;
    }

    .attente-card__icon {
      color: var(--cg-orange-2);
      flex: none;
    }

    .attente-card__body {
      min-width: 0;
    }

    .attente-card__headline {
      margin: 0;
      font-weight: 600;
      color: var(--cg-orange-2);
    }

    .attente-card__what {
      margin: var(--cg-space-1) 0 0;
      overflow-wrap: anywhere;
    }

    .attente-card__detail,
    .attente-card__settled {
      margin: var(--cg-space-1) 0 0;
      font-size: 13px;
      opacity: 0.8;
    }

    .attente-card__gestures {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .attente-card__confirm {
      --mdc-filled-button-container-color: var(--cg-orange-2);
      --mdc-filled-button-label-text-color: var(--cg-navy);
    }

    .attente-card__dismiss {
      --mdc-text-button-label-text-color: var(--cg-orange-2);
    }
  `,
})
export class AttenteCardComponent {
  readonly attente = input.required<AtelierTerminalAttente>();
  /** L'état vivant de l'attente, lu sur le tableau ; `undefined` si inconnu (rechargé, hors portée). */
  readonly live = input<TerminalAction | null | undefined>(undefined);
  readonly readOnly = input(false);

  /** Un geste a été fait : le terminal relit le tableau. */
  readonly changed = output<void>();

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly busy = signal(false);
  /** Ce que le geste a donné, dit en clair sur la carte. */
  readonly outcome = signal<string | null>(null);

  readonly headline = computed(() => attenteHeadline(this.attente()));
  readonly detail = computed(() => attenteDetail(this.attente()));
  readonly icon = computed(() => {
    switch (this.attente().kind) {
      case 'PROPOSED': return 'task_alt';
      case 'REQUESTED': return 'outgoing_mail';
      case 'ALREADY': return 'content_copy';
      default: return 'pending_actions';
    }
  });

  /**
   * La proposition attend-elle encore un geste ? Si le tableau connaît l'attente, il fait foi ; sinon
   * on se fie à l'instantané — au pire le serveur rend l'attente telle quelle (sans effet).
   */
  readonly pending = computed(() => {
    const live = this.live();
    if (live === undefined) {
      return true;
    }
    if (live === null) {
      return false;
    }
    return !!live.proposedStatus && (live.status === 'A_FAIRE' || live.status === 'DEMANDE');
  });

  confirm(): void {
    const a = this.attente();
    this.busy.set(true);
    this.service.confirmProposal(a.workspaceId, a.actionId).subscribe({
      next: () => {
        this.outcome.set('Confirmé : l\'attente est fermée.');
        this.busy.set(false);
        this.changed.emit();
      },
      error: () => this.fail(),
    });
  }

  dismiss(): void {
    const a = this.attente();
    this.busy.set(true);
    this.service.dismissProposal(a.workspaceId, a.actionId).subscribe({
      next: () => {
        this.outcome.set('Laissée ouverte.');
        this.busy.set(false);
        this.changed.emit();
      },
      error: () => this.fail(),
    });
  }

  private fail(): void {
    this.busy.set(false);
    this.snackBar.open('L\'attente n\'a pas pu être mise à jour.', 'Fermer', { duration: 5000 });
  }
}
