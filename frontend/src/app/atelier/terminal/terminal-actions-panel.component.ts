import { Component, HostListener, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';

import {
  TERMINAL_ACTION_STATUS_LABELS,
  TerminalAction,
  TerminalActionBoard,
  TerminalActionStatus,
  isOpenStatus,
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

/** La portée affichée : ce terminal seulement, ou tout le poste. */
export type AttentesScope = 'here' | 'host';

/** Une colonne du panneau. */
interface Column {
  key: 'todo' | 'asked' | 'done';
  title: string;
  empty: string;
  items: TerminalAction[];
}

/**
 * **Le panneau des attentes** (F-175 / SF-175-04, évolution du menu de F-154 / SF-154-03). Un état
 * d'écran à droite du terminal, comme le panneau d'une page ; Échap le ferme.
 *
 * <p>Trois colonnes — **À faire · Demandé · Fait récemment** — sur ce terminal ou tout le poste, avec
 * les gestes de l'utilisateur : ajouter, éditer, passer d'un état à l'autre, confirmer ou écarter une
 * fermeture proposée par l'agent, rétablir ce qui a été fermé depuis 7 jours. Une attente née dans un
 * autre terminal du poste se traite d'ici : elle porte le nom de son terminal.</p>
 *
 * <p>**Un échec remet l'écran dans l'état d'avant, et le dit** : un écran qui ment sur une attente est
 * pire qu'une erreur.</p>
 */
@Component({
  selector: 'app-terminal-actions-panel',
  imports: [
    FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatTooltipModule,
  ],
  template: `
    <aside class="actions-panel" role="complementary" aria-label="Attentes">
      <header class="actions-panel__bar">
        <h2 class="actions-panel__title">Attentes</h2>
        <span class="actions-panel__spacer"></span>
        <button mat-icon-button type="button" class="actions-panel__close" matTooltip="Fermer (Échap)"
          aria-label="Fermer les attentes" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>

      <div class="actions-panel__tools">
        @if (hasHost()) {
          <mat-button-toggle-group class="actions-panel__scope" [value]="scope()"
            (change)="scope.set($event.value)" aria-label="Portée">
            <mat-button-toggle value="here">Ce terminal</mat-button-toggle>
            <mat-button-toggle value="host">Tout le poste</mat-button-toggle>
          </mat-button-toggle-group>
        }
        <span class="actions-panel__spacer"></span>
        <button mat-stroked-button type="button" class="actions-panel__add" (click)="adding.set(!adding())">
          <mat-icon>add</mat-icon>
          Ajouter
        </button>
      </div>

      @if (adding()) {
        <form class="actions-panel__form" (submit)="$event.preventDefault(); add()">
          <mat-form-field appearance="outline" class="actions-panel__field">
            <mat-label>Ce qu'il faut obtenir</mat-label>
            <input matInput name="description" [(ngModel)]="newDescription" maxlength="300" required
              placeholder="Demander l'accès VPN à Karim">
          </mat-form-field>
          <mat-form-field appearance="outline" class="actions-panel__field">
            <mat-label>Qui (facultatif)</mat-label>
            <input matInput name="person" [(ngModel)]="newPerson" maxlength="120">
          </mat-form-field>
          <div class="actions-panel__form-gestures">
            <button mat-flat-button type="submit" class="actions-panel__save"
              [disabled]="!newDescription.trim() || busy() === 'new'">Ajouter</button>
            <button mat-button type="button" (click)="adding.set(false)">Annuler</button>
          </div>
        </form>
      }

      <div class="actions-panel__content">
        @if (failed()) {
          <p class="actions-panel__empty">Les attentes n'ont pas pu être chargées.</p>
        } @else if (loading()) {
          <p class="actions-panel__empty">Chargement…</p>
        } @else {
          <div class="columns">
            @for (column of columns(); track column.key) {
              <section class="column" [attr.aria-label]="column.title">
                <h3 class="column__title">{{ column.title }} <span class="column__count">{{ column.items.length }}</span></h3>
                @if (!column.items.length) {
                  <p class="column__empty">{{ column.empty }}</p>
                }
                @for (action of column.items; track action.id) {
                  <article class="action" [class.action--closed]="!isOpen(action)">
                    @if (action.workspaceId !== workspaceId()) {
                      <p class="action__project">né dans « {{ action.workspaceName }} »</p>
                    }
                    @if (editing() === action.id) {
                      <form class="action__edit" (submit)="$event.preventDefault(); saveEdit(action)">
                        <mat-form-field appearance="outline" class="actions-panel__field">
                          <mat-label>Attente</mat-label>
                          <input matInput name="edit" [(ngModel)]="editDescription" maxlength="300" required>
                        </mat-form-field>
                        <div class="action__gestures">
                          <button mat-button type="submit" [disabled]="!editDescription.trim()">Enregistrer</button>
                          <button mat-button type="button" (click)="editing.set(null)">Annuler</button>
                        </div>
                      </form>
                    } @else {
                      <p class="action__what">{{ action.description }}</p>
                    }
                    @if (action.blocks) {
                      <p class="action__blocks">↳ bloque : {{ action.blocks }}</p>
                    }
                    <p class="action__meta">
                      @if (action.status === 'DEMANDE') {
                        demandé{{ action.requestedTo ? ' à ' + action.requestedTo : '' }}{{ action.channel ? ' par ' + action.channel : '' }}
                        · {{ age(action.requestedAt || action.createdAt) }}
                      } @else if (isOpen(action)) {
                        @if (action.person) { <span class="action__person">{{ action.person }}</span> · }
                        <span class="action__age">{{ age(action.createdAt) }}</span>
                      } @else {
                        {{ label(action.status) }}{{ action.closedReason ? ' — « ' + action.closedReason + ' »' : '' }}
                      }
                    </p>

                    @if (action.proposedStatus && isOpen(action)) {
                      <div class="action__proposal" role="group" aria-label="Fermeture proposée">
                        <p class="action__proposal-text">
                          L'agent pense que c'est {{ action.proposedStatus === 'ANNULE' ? 'sans objet' : 'réglé' }}@if (action.proposedReason) { : « {{ action.proposedReason }} »}.
                        </p>
                        <div class="action__gestures">
                          <button mat-flat-button type="button" class="action__confirm" [disabled]="busy() === action.id"
                            (click)="confirm(action)">Confirmer</button>
                          <button mat-button type="button" class="action__dismiss" [disabled]="busy() === action.id"
                            (click)="dismiss(action)">Pas encore</button>
                        </div>
                      </div>
                    }

                    <div class="action__gestures">
                      @switch (action.status) {
                        @case ('A_FAIRE') {
                          <button mat-button type="button" class="action__ask" [disabled]="busy() === action.id"
                            (click)="move(action, 'DEMANDE')">Demandé</button>
                          <button mat-button type="button" class="action__done" [disabled]="busy() === action.id"
                            (click)="move(action, 'FAIT')"><mat-icon>check</mat-icon>Fait</button>
                        }
                        @case ('DEMANDE') {
                          <button mat-button type="button" class="action__done" [disabled]="busy() === action.id"
                            (click)="move(action, 'FAIT')"><mat-icon>check</mat-icon>Fait</button>
                          <button mat-button type="button" class="action__redo" [disabled]="busy() === action.id"
                            matTooltip="Pas de réponse : à refaire" (click)="move(action, 'A_FAIRE')">À refaire</button>
                        }
                        @default {
                          <button mat-button type="button" class="action__reopen" [disabled]="busy() === action.id"
                            (click)="reopen(action)">Rétablir</button>
                        }
                      }
                      @if (isOpen(action)) {
                        <button mat-icon-button type="button" class="action__edit-btn" matTooltip="Modifier"
                          aria-label="Modifier l'attente" (click)="startEdit(action)"><mat-icon>edit</mat-icon></button>
                        <button mat-icon-button type="button" class="action__cancel" matTooltip="Annuler l'attente"
                          aria-label="Annuler l'attente" [disabled]="busy() === action.id"
                          (click)="move(action, 'ANNULE')"><mat-icon>block</mat-icon></button>
                      }
                    </div>
                  </article>
                }
              </section>
            }
          </div>
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
      width: min(880px, 70vw);
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
      /* ÎLOT CLAIR posé sur le terminal sombre (même remède que le panneau d'une page, F-30). */
      --mdc-icon-button-icon-color: var(--cg-text-secondary);
      color: var(--cg-text-primary);
    }

    .actions-panel__bar,
    .actions-panel__tools {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2) var(--cg-space-1) var(--cg-space-3);
    }

    .actions-panel__bar {
      border-bottom: 1px solid var(--cg-divider);
    }

    .actions-panel__tools {
      flex-wrap: wrap;
      padding-top: var(--cg-space-2);
      padding-bottom: var(--cg-space-2);
    }

    .actions-panel__title {
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
    }

    .actions-panel__spacer {
      flex: 1;
    }

    .actions-panel__form {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      padding: 0 var(--cg-space-3);
    }

    .actions-panel__field {
      flex: 1 1 240px;
    }

    .actions-panel__form-gestures {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .actions-panel__content {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: var(--cg-space-2);
    }

    .actions-panel__empty,
    .column__empty {
      margin: var(--cg-space-2);
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .columns {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--cg-space-2);
      align-items: start;
    }

    @media (max-width: 899px) {
      .columns {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .column {
      padding: var(--cg-space-2);
      border-radius: 8px;
      background: var(--cg-surface-2);
      min-width: 0;
    }

    .column__title {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-2);
      font-size: 14px;
      font-weight: 600;
      color: var(--cg-primary);
    }

    .column__count {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-surface);
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .action {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
      background: var(--cg-surface);
    }

    .action__what {
      margin: 0;
      font-weight: 600;
      overflow-wrap: anywhere;
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
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      margin-top: var(--cg-space-1);
    }

    .action__proposal {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border-left: 4px solid var(--cg-accent);
      background: var(--cg-surface-2);
      border-radius: 4px;
    }

    .action__proposal-text {
      margin: 0;
      font-size: 13px;
    }

    .action--closed .action__what {
      text-decoration: line-through;
      color: var(--cg-text-secondary);
      font-weight: 400;
    }
  `,
})
export class TerminalActionsPanelComponent implements OnInit {
  /** Le terminal courant. */
  readonly workspaceId = input.required<string>();

  /** Fermeture demandée (croix, Échap). */
  readonly closed = output<void>();

  /** Le tableau a changé : la bande et la pastille le relisent. */
  readonly changed = output<TerminalActionBoard>();

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly board = signal<TerminalActionBoard | null>(null);
  readonly scope = signal<AttentesScope>('here');
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);
  readonly adding = signal(false);
  readonly editing = signal<string | null>(null);

  newDescription = '';
  newPerson = '';
  editDescription = '';

  readonly hasHost = computed(() => !!this.board()?.hostId);

  /** Les attentes visibles selon la portée. */
  readonly visible = computed<TerminalAction[]>(() => {
    const b = this.board();
    if (!b) {
      return [];
    }
    return this.scope() === 'host' ? [...b.here, ...b.host] : b.here;
  });

  readonly columns = computed<Column[]>(() => {
    const all = this.visible();
    const byAge = (a: TerminalAction, b: TerminalAction) => a.createdAt.localeCompare(b.createdAt);
    return [
      { key: 'todo', title: 'À faire', empty: 'Rien à faire.',
        items: all.filter(a => a.status === 'A_FAIRE').sort(byAge) },
      { key: 'asked', title: 'Demandé', empty: 'Aucune demande en cours.',
        items: all.filter(a => a.status === 'DEMANDE').sort(byAge) },
      { key: 'done', title: 'Fait récemment', empty: 'Rien de fermé ces 7 derniers jours.',
        items: all.filter(a => !isOpenStatus(a.status))
          .sort((a, b) => (b.closedAt ?? '').localeCompare(a.closedAt ?? '')) },
    ];
  });

  ngOnInit(): void {
    this.reload();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  age(at: string): string {
    return ageLabel(at);
  }

  label(status: TerminalActionStatus): string {
    return TERMINAL_ACTION_STATUS_LABELS[status];
  }

  isOpen(action: TerminalAction): boolean {
    return isOpenStatus(action.status);
  }

  /** Relit le tableau ; prévient le terminal pour que la bande suive. */
  reload(): void {
    this.service.board(this.workspaceId()).subscribe({
      next: board => {
        this.board.set(board);
        this.loading.set(false);
        this.failed.set(false);
        this.changed.emit(board);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Change l'état — l'attente est traitée sur la route de SON terminal (née ailleurs sur le poste). */
  move(action: TerminalAction, status: TerminalActionStatus): void {
    this.apply(action, { ...action, status },
      this.service.changeStatus(action.workspaceId, action.id, { status }));
  }

  confirm(action: TerminalAction): void {
    this.apply(action, { ...action, status: action.proposedStatus ?? action.status, proposedStatus: null },
      this.service.confirmProposal(action.workspaceId, action.id));
  }

  dismiss(action: TerminalAction): void {
    this.apply(action, { ...action, proposedStatus: null, proposedReason: null },
      this.service.dismissProposal(action.workspaceId, action.id));
  }

  reopen(action: TerminalAction): void {
    this.apply(action, { ...action, status: action.requestedAt ? 'DEMANDE' : 'A_FAIRE' },
      this.service.reopen(action.workspaceId, action.id));
  }

  startEdit(action: TerminalAction): void {
    this.editDescription = action.description;
    this.editing.set(action.id);
  }

  saveEdit(action: TerminalAction): void {
    const description = this.editDescription.trim();
    if (!description) {
      return;
    }
    this.editing.set(null);
    this.apply(action, { ...action, description },
      this.service.edit(action.workspaceId, action.id, { description }));
  }

  add(): void {
    const description = this.newDescription.trim();
    if (!description) {
      return;
    }
    this.busy.set('new');
    this.service.create(this.workspaceId(), { description, person: this.newPerson.trim() || null })
      .subscribe({
        next: created => {
          this.patchBoard(b => ({ ...b, here: [...b.here, { ...created, workspaceName: null }] }));
          this.newDescription = '';
          this.newPerson = '';
          this.adding.set(false);
          this.busy.set(null);
          this.reload();
        },
        error: () => this.fail(),
      });
  }

  /**
   * Applique un geste : l'écran change **tout de suite** (optimiste), la réponse du serveur fait foi,
   * et un échec **remet l'écran dans l'état d'avant**.
   */
  private apply(action: TerminalAction, optimistic: TerminalAction, call: Observable<TerminalAction>): void {
    const before = this.board();
    this.busy.set(action.id);
    this.replace(optimistic);
    call.subscribe({
      next: saved => {
        this.replace({ ...saved, workspaceName: action.workspaceName });
        this.busy.set(null);
        this.reload();
      },
      error: () => {
        this.board.set(before);
        this.fail();
      },
    });
  }

  private replace(updated: TerminalAction): void {
    this.patchBoard(b => ({
      ...b,
      here: b.here.map(a => (a.id === updated.id ? updated : a)),
      host: b.host.map(a => (a.id === updated.id ? updated : a)),
    }));
  }

  private patchBoard(change: (b: TerminalActionBoard) => TerminalActionBoard): void {
    const b = this.board();
    if (b) {
      this.board.set(change(b));
    }
  }

  private fail(): void {
    this.busy.set(null);
    this.snackBar.open("L'attente n'a pas pu être mise à jour.", 'Fermer', { duration: 5000 });
  }
}
