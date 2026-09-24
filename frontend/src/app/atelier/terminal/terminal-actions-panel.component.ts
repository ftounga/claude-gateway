import { Component, HostListener, OnInit, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  TerminalAction,
  TerminalActionElsewhere,
} from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

/** Depuis quand l'action attend — l'ancienneté est le signal qui fait agir. */
export function ageLabel(createdAt: string, now = Date.now()): string {
  const started = Date.parse(createdAt);
  if (Number.isNaN(started)) {
    return '';
  }
  const minutes = Math.max(0, Math.round((now - started) / 60000));
  if (minutes < 60) {
    return minutes <= 1 ? "à l'instant" : `il y a ${minutes} min`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `il y a ${hours} h`;
  }
  const days = Math.round(hours / 24);
  return days === 1 ? 'hier' : `il y a ${days} jours`;
}

/**
 * **Le menu des actions à faire, à droite du terminal** (F-154 / SF-154-03). Patron du panneau d'une
 * page (F-109 / SF-109-03) : un état d'écran, pas une route. Échap le ferme.
 *
 * <p>Ce qu'il montre : les actions ouvertes de **ce** terminal, les plus anciennes d'abord — puis,
 * repliées et en lecture seule, celles des **autres** projets, pour tout voir d'un endroit sans
 * perdre le contexte courant.</p>
 *
 * <p>Une action fermée **reste un instant, barrée, avec Rétablir** : sans ce repentir, un clic
 * malheureux serait définitif.</p>
 */
@Component({
  selector: 'app-terminal-actions-panel',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <aside class="actions-panel" role="complementary" aria-label="Actions à faire">
      <header class="actions-panel__bar">
        <h2 class="actions-panel__title">À faire</h2>
        <span class="actions-panel__spacer"></span>
        <button mat-icon-button type="button" class="actions-panel__close" matTooltip="Fermer (Échap)"
          aria-label="Fermer les actions à faire" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>

      <div class="actions-panel__content">
        @if (failed()) {
          <p class="actions-panel__empty">Les actions n'ont pas pu être chargées.</p>
        } @else if (loading()) {
          <p class="actions-panel__empty">Chargement…</p>
        } @else if (!actions().length && !closedRecently().length) {
          <p class="actions-panel__empty">Rien à faire de votre côté pour ce terminal.</p>
        }

        @for (action of actions(); track action.id) {
          <article class="action">
            <p class="action__what">{{ action.description }}</p>
            @if (action.blocks) {
              <p class="action__blocks">↳ bloque : {{ action.blocks }}</p>
            }
            <p class="action__meta">
              @if (action.person) { <span class="action__person">{{ action.person }}</span> · }
              <span class="action__age">{{ age(action.createdAt) }}</span>
            </p>
            <div class="action__gestures">
              <button mat-button type="button" class="action__done" [disabled]="busy() === action.id"
                (click)="markDone(action)">
                <mat-icon>check</mat-icon>
                C'est fait
              </button>
              <button mat-button type="button" class="action__cancel" [disabled]="busy() === action.id"
                (click)="cancel(action)">
                Annuler
              </button>
            </div>
          </article>
        }

        @for (action of closedRecently(); track action.id) {
          <article class="action action--closed">
            <p class="action__what">{{ action.description }}</p>
            <div class="action__gestures">
              <span class="action__settled">{{ action.status === 'DONE' ? 'Fait' : 'Annulée' }}</span>
              <button mat-button type="button" class="action__reopen" [disabled]="busy() === action.id"
                (click)="reopen(action)">
                Rétablir
              </button>
            </div>
          </article>
        }

        @if (elsewhere().length) {
          <section class="elsewhere">
            <button mat-button type="button" class="elsewhere__toggle"
              [attr.aria-expanded]="elsewhereOpen()" (click)="elsewhereOpen.set(!elsewhereOpen())">
              <mat-icon>{{ elsewhereOpen() ? 'expand_less' : 'expand_more' }}</mat-icon>
              Ailleurs ({{ elsewhere().length }})
            </button>
            @if (elsewhereOpen()) {
              @for (action of elsewhere(); track action.id) {
                <article class="action action--elsewhere">
                  <p class="action__project">{{ action.workspaceName }}</p>
                  <p class="action__what">{{ action.description }}</p>
                  <p class="action__meta"><span class="action__age">{{ age(action.createdAt) }}</span></p>
                </article>
              }
            }
          </section>
        }
      </div>
    </aside>
  `,
  styles: `
    :host {
      position: fixed;
      top: 64px;
      right: 0;
      bottom: 0;
      z-index: 900;
      width: min(420px, 40vw);
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    }

    @media (max-width: 899px) {
      :host {
        width: 100vw;
      }
    }

    .actions-panel {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--cg-surface);
      border-left: 1px solid var(--cg-divider);
      /* ÎLOT CLAIR posé sur le terminal sombre : celui-ci redéfinit l'encre des boutons-icônes pour
         une surface sombre (F-30 / SF-30-15), qui se propagerait ici par héritage et rendrait Fermer
         clair-sur-blanc. Même remède que le panneau d'une page. */
      --mdc-icon-button-icon-color: var(--cg-text-secondary);
      color: var(--cg-text);
    }

    .actions-panel__bar {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2) var(--cg-space-1) var(--cg-space-3);
      border-bottom: 1px solid var(--cg-divider);
    }

    .actions-panel__title {
      margin: 0;
      font-size: 18px;
      font-weight: 600;
    }

    .actions-panel__spacer {
      flex: 1;
    }

    .actions-panel__content {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: var(--cg-space-2);
    }

    .actions-panel__empty {
      margin: var(--cg-space-4) var(--cg-space-2);
      color: var(--cg-text-secondary);
    }

    .action {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
    }

    .action__what {
      margin: 0;
      font-weight: 600;
    }

    .action__blocks,
    .action__meta,
    .action__project {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .action__project {
      font-weight: 600;
      margin: 0 0 var(--cg-space-1);
    }

    .action__gestures {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-1);
    }

    .action--closed .action__what {
      text-decoration: line-through;
      color: var(--cg-text-secondary);
      font-weight: 400;
    }

    .action__settled {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .action--elsewhere {
      background: var(--cg-bg);
    }

    .elsewhere {
      margin-top: var(--cg-space-3);
      border-top: 1px solid var(--cg-divider);
      padding-top: var(--cg-space-2);
    }
  `,
})
export class TerminalActionsPanelComponent implements OnInit {
  /** Le terminal courant. */
  readonly workspaceId = input.required<string>();

  /** Fermeture demandée (croix, Échap). */
  readonly closed = output<void>();

  /** Le nombre d'actions ouvertes a changé : la pastille de la barre le suit. */
  readonly countChanged = output<number>();

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly actions = signal<TerminalAction[]>([]);
  readonly closedRecently = signal<TerminalAction[]>([]);
  readonly elsewhere = signal<TerminalActionElsewhere[]>([]);
  readonly elsewhereOpen = signal(false);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);

  ngOnInit(): void {
    this.service.list(this.workspaceId()).subscribe({
      next: actions => {
        this.actions.set(actions);
        this.countChanged.emit(actions.length);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
    this.service.elsewhere(this.workspaceId()).subscribe({
      next: actions => this.elsewhere.set(actions),
      // Silencieux : « Ailleurs » est un bonus, son échec ne doit pas masquer la liste du terminal.
      error: () => this.elsewhere.set([]),
    });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  age(createdAt: string): string {
    return ageLabel(createdAt);
  }

  markDone(action: TerminalAction): void {
    this.settle(action, this.service.close(this.workspaceId(), action.id));
  }

  cancel(action: TerminalAction): void {
    this.settle(action, this.service.cancel(this.workspaceId(), action.id));
  }

  /** Rouvre une action fermée par erreur — et la remet à sa place dans la liste. */
  reopen(action: TerminalAction): void {
    this.busy.set(action.id);
    this.service.reopen(this.workspaceId(), action.id).subscribe({
      next: reopened => {
        this.closedRecently.update(list => list.filter(a => a.id !== action.id));
        this.actions.update(list => [...list, reopened]
          .sort((a, b) => a.createdAt.localeCompare(b.createdAt)));
        this.countChanged.emit(this.actions().length);
        this.busy.set(null);
      },
      error: () => this.fail(),
    });
  }

  /**
   * Ferme une action : elle sort de la liste et rejoint le repentir. **En cas d'échec, l'écran
   * revient à ce qu'il montrait** — un écran qui ment sur une action à faire est pire qu'une erreur.
   */
  private settle(action: TerminalAction, call: { subscribe: (o: {
    next: (a: TerminalAction) => void; error: () => void }) => unknown }): void {
    this.busy.set(action.id);
    const before = this.actions();
    this.actions.update(list => list.filter(a => a.id !== action.id));
    this.countChanged.emit(this.actions().length);
    call.subscribe({
      next: settled => {
        this.closedRecently.update(list => [...list, settled]);
        this.busy.set(null);
      },
      error: () => {
        this.actions.set(before);
        this.countChanged.emit(before.length);
        this.fail();
      },
    });
  }

  private fail(): void {
    this.busy.set(null);
    this.snackBar.open("L'action n'a pas pu être mise à jour.", 'Fermer', { duration: 5000 });
  }
}
