import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';

import {
  CATEGORIE_LABELS, INTERLOCUTEUR_MERGE_LABELS, Interlocuteur, InterlocuteurBoard, InterlocuteurBoardRow,
  InterlocuteurMergeSuggestion, TerminalActionCategorie, ageLabel, daysLabel, groupedFollowUpDraft,
} from '../../core/models/terminal-actions.models';

import { TerminalActionsService } from '../../core/services/terminal-actions.service';

/**
 * **La vue « Personnes » du panneau des attentes** (F-180 / SF-180-03, décision D2) : les interlocuteurs
 * du poste — un par personne, plus un nom écrit trois façons —, leur rôle et leur équipe, leur lien au
 * Radar, et les fusions proposées en tête : [Fusionner] [Ce sont deux personnes].
 *
 * <p>Un échec remet l'écran dans l'état d'avant, et le dit.</p>
 */
@Component({
  selector: 'app-terminal-people-view',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatTooltipModule],
  template: `
    @if (board()?.byCategorie?.length) {
      <!-- F-180 / SF-180-05 (D7) — où ça coince : le délai moyen demande → réponse par nature. -->
      <p class="people__measure" aria-label="Délai de réponse par nature">
        @for (c of board()!.byCategorie; track c.categorie) {
          <span class="people__chip">{{ categorieLabel(c.categorie) }} :
            {{ c.avgResponseDays !== null ? days(c.avgResponseDays) : '—' }}
            <span class="people__muted-inline">({{ c.openCount }} ouverte{{ c.openCount > 1 ? 's' : '' }})</span></span>
        }
      </p>
    }

    @if (board()?.people?.length) {
      <!-- F-180 / SF-180-05 (D5) — QUI BLOQUE QUOI : relancer une fois par personne, jamais sans geste. -->
      <section class="board" aria-label="Attentes par personne">
        <h3 class="people__title">Qui bloque quoi</h3>
        @for (row of board()!.people; track row.interlocuteurId ?? row.name) {
          <article class="board__row">
            <div class="person__head">
              <p class="person__name">{{ row.name }}@if (row.role) { <span class="people__muted-inline"> · {{ row.role }}</span> }</p>
              <span class="person__count">{{ row.openCount }} attente{{ row.openCount > 1 ? 's' : '' }}</span>
            </div>
            <p class="people__muted">
              la plus ancienne {{ age(row.oldestOpenAt) }}
              @if (row.subjects.length) { · bloque {{ row.subjects.join(', ') }} }
            </p>
            <p class="people__muted board__delay">
              @if (row.avgResponseDays !== null) {
                {{ firstName(row.name) }} répond en {{ days(row.avgResponseDays) }} en moyenne
                ({{ row.responseCount }} réponse{{ row.responseCount > 1 ? 's' : '' }} mesurée{{ row.responseCount > 1 ? 's' : '' }})
              } @else {
                pas encore de réponse mesurée
              }
            </p>
            <div class="people__gestures">
              <button mat-flat-button type="button" class="board__followup"
                matTooltip="Dépose un brouillon groupé dans la saisie — rien n'est envoyé"
                (click)="relancer(row, false)"><mat-icon>reply_all</mat-icon>Relancer {{ firstName(row.name) }} ({{ row.openCount }})</button>
              <button mat-button type="button" class="board__email"
                matTooltip="Le brouillon demande à l'agent de vous l'envoyer par courriel, à transférer"
                (click)="relancer(row, true)">Par courriel à moi</button>
              <button mat-button type="button" class="board__toggle" (click)="toggle(row)">
                {{ isExpanded(row) ? 'Masquer' : 'Voir les attentes' }}
              </button>
            </div>
            @if (isExpanded(row)) {
              <ul class="board__list">
                @for (a of row.open; track a.id) {
                  <li>{{ a.description }} <span class="people__muted-inline">· {{ age(a.requestedAt || a.createdAt) }}{{ a.workspaceName ? ' · ' + a.workspaceName : '' }}</span></li>
                }
              </ul>
            }
          </article>
        }
      </section>
    }

    @if (suggestions().length) {
      <section class="people-merge" aria-label="Fusions proposées">
        <h3 class="people__title">Même personne ? ({{ suggestions().length }})</h3>
        @for (s of suggestions(); track s.merge.id) {
          <article class="people-merge__row">
            <p class="people-merge__names">« {{ s.merge.displayName }} » → « {{ s.keep.displayName }} »
              <span class="people__muted">· {{ reasonLabel(s) }}</span></p>
            <div class="people__gestures">
              <button mat-flat-button type="button" class="people-merge__do" [disabled]="busy()"
                (click)="merge(s)">Fusionner</button>
              <button mat-button type="button" class="people-merge__distinct" [disabled]="busy()"
                (click)="distinct(s)">Ce sont deux personnes</button>
            </div>
          </article>
        }
      </section>
    }
    @if (failed()) {
      <p class="people__muted">Les interlocuteurs n'ont pas pu être chargés.</p>
    } @else if (!people().length && !board()?.people?.length) {
      <p class="people__muted">Aucun interlocuteur sur ce poste pour l'instant : ils naissent des attentes qui nomment quelqu'un.</p>
    }
    @if (people().length) {
      <h3 class="people__title people__directory">Annuaire du poste</h3>
    }
    @for (p of people(); track p.id) {
      <article class="person">
        @if (editing() === p.id) {
          <form class="person__edit" (submit)="$event.preventDefault(); save(p)">
            <mat-form-field appearance="outline" class="person__field">
              <mat-label>Nom</mat-label>
              <input matInput name="name" [(ngModel)]="editName" maxlength="120" required>
            </mat-form-field>
            <mat-form-field appearance="outline" class="person__field">
              <mat-label>Rôle</mat-label>
              <input matInput name="role" [(ngModel)]="editRole" maxlength="120">
            </mat-form-field>
            <mat-form-field appearance="outline" class="person__field">
              <mat-label>Équipe</mat-label>
              <input matInput name="team" [(ngModel)]="editTeam" maxlength="120">
            </mat-form-field>
            <div class="people__gestures">
              <button mat-button type="submit" [disabled]="!editName.trim() || busy()">Enregistrer</button>
              <button mat-button type="button" (click)="editing.set(null)">Annuler</button>
            </div>
          </form>
        } @else {
          <div class="person__head">
            <p class="person__name">{{ p.displayName }}</p>
            <span class="person__count">{{ p.openCount }} attente{{ p.openCount > 1 ? 's' : '' }}</span>
            <button mat-icon-button type="button" class="person__edit-btn" matTooltip="Modifier"
              aria-label="Modifier l'interlocuteur" (click)="startEdit(p)"><mat-icon>edit</mat-icon></button>
          </div>
          <p class="people__muted">
            {{ roleLine(p) }}
            @if (p.radarPersonId) { · <span class="person__radar">dans le Radar{{ p.radarName ? ' (' + p.radarName + ')' : '' }}</span> }
          </p>
        }
      </article>
    }
  `,
  styles: `
    :host { display: block; }

    .people__title {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: var(--cg-primary);
    }

    .people__muted {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .people__gestures {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1);
      margin-top: var(--cg-space-1);
    }

    .people__measure {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1);
      margin: 0 0 var(--cg-space-2);
    }

    .people__chip {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-surface-2);
      font-size: 12px;
    }

    .people__muted-inline {
      color: var(--cg-text-secondary);
      font-size: 12px;
      font-weight: 400;
    }

    .people__directory {
      margin-top: var(--cg-space-3);
      margin-bottom: var(--cg-space-2);
    }

    .board {
      margin-bottom: var(--cg-space-3);
    }

    .board__row {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-orange-2);
      border-radius: 8px;
      background: var(--cg-surface);
    }

    .board__followup {
      --mdc-filled-button-container-color: var(--cg-accent);
    }

    .board__list {
      margin: var(--cg-space-1) 0 0;
      padding-left: var(--cg-space-4);
      font-size: 13px;
    }

    .people-merge {
      margin-bottom: var(--cg-space-3);
      padding: var(--cg-space-3);
      border: 1px dashed var(--cg-accent);
      border-radius: 8px;
      background: var(--cg-surface-2);
    }

    .people-merge__row {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border-radius: 8px;
      background: var(--cg-surface);
    }

    .people-merge__names {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .person {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
      background: var(--cg-surface);
    }

    .person__head {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .person__name {
      flex: 1;
      margin: 0;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .person__count {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-surface-2);
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .person__radar {
      color: var(--cg-primary);
    }

    .person__edit {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
    }

    .person__field {
      flex: 1 1 160px;
    }
  `,
})
export class TerminalPeopleViewComponent implements OnInit {
  readonly workspaceId = input.required<string>();

  /** Les interlocuteurs ont changé (fusion) : le tableau se relit. */
  readonly changed = output<void>();

  /** La relance groupée : le brouillon à déposer dans la saisie, **jamais envoyé** (F-180 / SF-180-05). */
  readonly followUp = output<string>();

  /** Qui bloque quoi (F-180 / SF-180-05). */
  readonly board = signal<InterlocuteurBoard | null>(null);
  private readonly expanded = signal<Set<string>>(new Set());

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly people = signal<Interlocuteur[]>([]);
  readonly suggestions = signal<InterlocuteurMergeSuggestion[]>([]);
  readonly failed = signal(false);
  readonly busy = signal(false);
  readonly editing = signal<string | null>(null);

  editName = '';
  editRole = '';
  editTeam = '';

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.service.interlocuteurs(this.workspaceId()).subscribe({
      next: list => {
        this.people.set(list ?? []);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
    this.service.interlocuteurBoard(this.workspaceId()).subscribe({
      next: b => this.board.set(b ?? null),
      error: () => this.board.set(null),
    });
    this.service.interlocuteurSuggestions(this.workspaceId()).subscribe({
      next: list => this.suggestions.set(list ?? []),
      error: () => this.suggestions.set([]),
    });
  }

  /** [Relancer Rémi (5)] : un seul brouillon groupé, déposé dans la saisie — rien n'est envoyé. */
  relancer(row: InterlocuteurBoardRow, viaEmail: boolean): void {
    this.followUp.emit(groupedFollowUpDraft(row, viaEmail));
  }

  toggle(row: InterlocuteurBoardRow): void {
    const key = row.interlocuteurId ?? row.name;
    this.expanded.update(set => {
      const next = new Set(set);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  isExpanded(row: InterlocuteurBoardRow): boolean {
    return this.expanded().has(row.interlocuteurId ?? row.name);
  }

  firstName(name: string): string {
    return name.split(' ')[0] || name;
  }

  days(value: number | null): string {
    return daysLabel(value);
  }

  age(at: string): string {
    return ageLabel(at);
  }

  categorieLabel(c: TerminalActionCategorie): string {
    return CATEGORIE_LABELS[c] ?? c;
  }

  roleLine(p: Interlocuteur): string {
    return [p.role, p.team].filter(x => !!x).join(' · ') || 'rôle non renseigné';
  }

  reasonLabel(s: InterlocuteurMergeSuggestion): string {
    return INTERLOCUTEUR_MERGE_LABELS[s.reason] ?? '';
  }

  merge(s: InterlocuteurMergeSuggestion): void {
    this.settle(s, this.service.mergeInterlocuteur(this.workspaceId(), s.merge.id, s.keep.id), true);
  }

  distinct(s: InterlocuteurMergeSuggestion): void {
    this.settle(s, this.service.distinctInterlocuteurs(this.workspaceId(), s.merge.id, s.keep.id), false);
  }

  startEdit(p: Interlocuteur): void {
    this.editName = p.displayName;
    this.editRole = p.role ?? '';
    this.editTeam = p.team ?? '';
    this.editing.set(p.id);
  }

  save(p: Interlocuteur): void {
    const before = this.people();
    const update = { displayName: this.editName.trim(), role: this.editRole.trim(), team: this.editTeam.trim() };
    this.editing.set(null);
    this.people.set(before.map(x => (x.id === p.id
      ? { ...x, displayName: update.displayName, role: update.role || null, team: update.team || null } : x)));
    this.busy.set(true);
    this.service.updateInterlocuteur(this.workspaceId(), p.id, update).subscribe({
      next: saved => {
        this.people.update(list => list.map(x => (x.id === p.id ? { ...saved, openCount: x.openCount } : x)));
        this.busy.set(false);
      },
      error: () => {
        this.people.set(before);
        this.fail();
      },
    });
  }

  private settle(s: InterlocuteurMergeSuggestion, call: Observable<unknown>, merged: boolean): void {
    const before = this.suggestions();
    this.busy.set(true);
    this.suggestions.set(before.filter(x => x.merge.id !== s.merge.id));
    call.subscribe({
      next: () => {
        this.busy.set(false);
        if (merged) {
          this.reload();
          this.changed.emit();
        }
      },
      error: () => {
        this.suggestions.set(before);
        this.fail();
      },
    });
  }

  private fail(): void {
    this.busy.set(false);
    this.snackBar.open("L'interlocuteur n'a pas pu être mis à jour.", 'Fermer', { duration: 5000 });
  }
}
