import { Component, HostListener, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';

import {
  AccesProposal,
  ageLabel,
  CATEGORIES,
  CATEGORIE_LABELS,
  DUPLICATE_REASON_LABELS,
  QUIS,
  QUI_LABELS,
  TERMINAL_ACTION_STATUS_LABELS,
  TerminalAction,
  TerminalActionBoard,
  TerminalActionCategorie,
  TerminalActionDuplicate,
  TerminalActionQualifyItem,
  TerminalActionQui,
  blockedStepLabel,
  dueLabel,
  TerminalActionReviewItem,
  TerminalActionStatus,
  followUpDraft,
  isOpenStatus,
} from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { TerminalPeopleViewComponent } from './terminal-people-view.component';
import { TerminalAccesViewComponent } from './terminal-acces-view.component';

/** Depuis quand l'action attend — défini avec les modèles (F-180 : partagé par les vues). */
export { ageLabel } from '../../core/models/terminal-actions.models';

/** La portée affichée : ce terminal seulement, ou tout le poste. */
export type AttentesScope = 'here' | 'host';

/** La vue du panneau (F-180) : les colonnes d'attentes, ou les personnes. */
export type AttentesView = 'columns' | 'people' | 'acces';

/** Une ligne de la reprise : l'attente héritée et l'état que l'utilisateur retient. */
interface ReviewRow {
  item: TerminalActionReviewItem;
  choice: TerminalActionStatus;
}

/** Une ligne de la reprise des natures (F-180 / SF-180-02) : la proposition et ce que l'utilisateur retient. */
interface QualifyRow {
  item: TerminalActionQualifyItem;
  categorie: TerminalActionCategorie;
  qui: TerminalActionQui;
}

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
    MatInputModule, MatSelectModule, MatTooltipModule, TerminalPeopleViewComponent,
    TerminalAccesViewComponent,
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
        <!-- F-180 : la vue — les attentes, les personnes (poste), le registre des accès. -->
        <mat-button-toggle-group class="actions-panel__view" [value]="view()"
          (change)="view.set($event.value)" aria-label="Vue">
          <mat-button-toggle value="columns">Attentes</mat-button-toggle>
          <mat-button-toggle value="people">Personnes</mat-button-toggle>
          <mat-button-toggle value="acces">Accès</mat-button-toggle>
        </mat-button-toggle-group>
        @if (hasHost() && view() === 'columns') {
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
          <mat-form-field appearance="outline" class="actions-panel__field actions-panel__field--small">
            <mat-label>Nature</mat-label>
            <mat-select name="categorie" [(ngModel)]="newCategorie">
              <mat-option [value]="null">—</mat-option>
              @for (c of categories; track c) { <mat-option [value]="c">{{ categorieLabel(c) }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="actions-panel__field actions-panel__field--small">
            <mat-label>Échéance (facultative)</mat-label>
            <input matInput type="date" name="dueAt" [(ngModel)]="newDue">
          </mat-form-field>
          <mat-form-field appearance="outline" class="actions-panel__field actions-panel__field--small">
            <mat-label>Qui agit</mat-label>
            <mat-select name="qui" [(ngModel)]="newQui">
              <mat-option [value]="null">—</mat-option>
              @for (q of quis; track q) { <mat-option [value]="q">{{ quiLabel(q) }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <div class="actions-panel__form-gestures">
            <button mat-flat-button type="submit" class="actions-panel__save"
              [disabled]="!newDescription.trim() || busy() === 'new'">Ajouter</button>
            <button mat-button type="button" (click)="adding.set(false)">Annuler</button>
          </div>
        </form>
      }

      <div class="actions-panel__content">
        @if (view() === 'people') {
          <app-terminal-people-view [workspaceId]="workspaceId()" (changed)="reload()" (followUp)="followUp.emit($event)"></app-terminal-people-view>
        } @else if (view() === 'acces') {
          <app-terminal-acces-view [workspaceId]="workspaceId()"></app-terminal-acces-view>
        } @else {
        @if (accesProposals().length) {
          <!-- F-180 / SF-180-04 — « AJOUTER AU REGISTRE ? » : un accès obtenu ne disparaît plus en « Fait ». -->
          <section class="review registry" aria-label="Ajouter au registre">
            <h3 class="review__title">Ajouter au registre ? ({{ accesProposals().length }})</h3>
            <p class="review__lead">Ces accès ont été obtenus. Au registre, l'agent ne les redemandera plus — sans aucun secret.</p>
            @for (p of accesProposals(); track p.action.id) {
              <article class="review__row">
                <p class="review__what">{{ p.system }}</p>
                <p class="review__where">obtenu le {{ p.obtainedAt.slice(8, 10) }}/{{ p.obtainedAt.slice(5, 7) }}{{ p.grantedBy ? ' par ' + p.grantedBy : '' }}</p>
                <div class="review__gestures">
                  <button mat-flat-button type="button" class="registry__add" [disabled]="busy() === p.action.id"
                    (click)="addToRegistry(p)">Ajouter</button>
                  <button mat-button type="button" class="registry__dismiss" [disabled]="busy() === p.action.id"
                    (click)="dismissRegistry(p)">Pas besoin</button>
                </div>
              </article>
            }
          </section>
        }
        @if (review().length && !reviewHidden()) {
          <!-- F-175 / SF-175-07 — LA REPRISE DE L'EXISTANT : présentée une fois, rien ne change sans validation. -->
          <section class="review" aria-label="Reprise des attentes existantes">
            <h3 class="review__title">Reprise : {{ review().length }} attente{{ review().length > 1 ? 's' : '' }} à vérifier</h3>
            <p class="review__lead">
              Ces attentes datent d'avant l'état « Demandé ». Un état vous est proposé pour chacune ;
              rien ne change sans votre validation.
            </p>
            @for (row of review(); track row.item.action.id) {
              <article class="review__row">
                <p class="review__what">{{ row.item.action.description }}</p>
                <p class="review__where">{{ row.item.action.workspaceName }} · {{ age(row.item.action.createdAt) }}</p>
                <div class="review__gestures">
                  <mat-button-toggle-group class="review__choice" [value]="row.choice"
                    (change)="choose(row.item.action.id, $event.value)" aria-label="État retenu">
                    <mat-button-toggle value="A_FAIRE">À faire</mat-button-toggle>
                    <mat-button-toggle value="DEMANDE">Demandé</mat-button-toggle>
                    <mat-button-toggle value="FAIT">Fait</mat-button-toggle>
                    <mat-button-toggle value="ANNULE">Annulé</mat-button-toggle>
                  </mat-button-toggle-group>
                  <button mat-button type="button" class="review__one" [disabled]="reviewBusy()"
                    (click)="validate([row])">Valider</button>
                </div>
              </article>
            }
            <div class="review__all">
              <button mat-flat-button type="button" class="review__validate-all" [disabled]="reviewBusy()"
                (click)="validate(review())">Tout valider</button>
              <button mat-button type="button" class="review__later" (click)="reviewHidden.set(true)">Plus tard</button>
            </div>
          </section>
        }
        @if (qualifyRows().length && !qualifyHidden()) {
          <!-- F-180 / SF-180-02 — LA REPRISE DES NATURES : devinées, jamais écrites sans validation. -->
          <section class="review qualify" aria-label="Nature des attentes">
            <h3 class="review__title">Nature : {{ qualifyRows().length }} attente{{ qualifyRows().length > 1 ? 's' : '' }} à qualifier</h3>
            <p class="review__lead">Une nature et « qui agit » vous sont proposés ; rien n'est écrit sans votre validation.</p>
            @for (row of qualifyRows(); track row.item.action.id) {
              <article class="review__row">
                <p class="review__what">{{ row.item.action.description }}</p>
                <div class="review__gestures">
                  <mat-form-field appearance="outline" class="actions-panel__field--small">
                    <mat-label>Nature</mat-label>
                    <mat-select [value]="row.categorie" (selectionChange)="chooseNature(row.item.action.id, $event.value, row.qui)">
                      @for (c of categories; track c) { <mat-option [value]="c">{{ categorieLabel(c) }}</mat-option> }
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="actions-panel__field--small">
                    <mat-label>Qui agit</mat-label>
                    <mat-select [value]="row.qui" (selectionChange)="chooseNature(row.item.action.id, row.categorie, $event.value)">
                      @for (q of quis; track q) { <mat-option [value]="q">{{ quiLabel(q) }}</mat-option> }
                    </mat-select>
                  </mat-form-field>
                  <button mat-button type="button" class="qualify__one" [disabled]="qualifyBusy()"
                    (click)="validateNature([row])">Valider</button>
                </div>
              </article>
            }
            <div class="review__all">
              <button mat-flat-button type="button" class="qualify__all" [disabled]="qualifyBusy()"
                (click)="validateNature(qualifyRows())">Tout valider</button>
              <button mat-button type="button" (click)="qualifyHidden.set(true)">Plus tard</button>
            </div>
          </section>
        }
        @if (duplicates().length) {
          <!-- F-180 / SF-180-01 — LES DOUBLONS PROBABLES : proposés, jamais fusionnés sans geste. -->
          <section class="dups" aria-label="Doublons probables">
            <h3 class="dups__title">Doublons probables ({{ duplicates().length }})</h3>
            @for (pair of duplicates(); track pair.duplicate.id) {
              <article class="dups__row">
                <p class="dups__keep">Garder : {{ pair.keep.description }}</p>
                <p class="dups__dup">Fusionner : {{ pair.duplicate.description }}
                  <span class="dups__reason">· {{ reasonLabel(pair) }}</span></p>
                <div class="action__gestures">
                  <button mat-flat-button type="button" class="dups__merge" [disabled]="busy() === pair.duplicate.id"
                    (click)="mergePair(pair)">Fusionner</button>
                  <button mat-button type="button" class="dups__dismiss" [disabled]="busy() === pair.duplicate.id"
                    (click)="notDuplicate(pair)">Ce n'est pas pareil</button>
                </div>
              </article>
            }
          </section>
        }
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
                    @if (action.categorie || action.qui || dueText(action)) {
                      <p class="action__tags">
                        @if (dueText(action)) {
                          <span class="tag" [class.tag--late]="action.dueState === 'OVERDUE' || action.dueState === 'TODAY'">{{ dueText(action) }}</span>
                        }
                        @if (action.categorie) { <span class="tag">{{ categorieLabel(action.categorie) }}</span> }
                        @if (action.qui) {
                          <span class="tag" [class.tag--moi]="action.qui === 'MOI'">{{ quiLabel(action.qui) }}</span>
                        }
                      </p>
                    }
                    @if (action.blocks) {
                      <p class="action__blocks">↳ bloque : {{ action.blocks }}</p>
                    }
                    @if (blockedLabel(action)) {
                      <p class="action__blocks action__step">↳ bloque : {{ blockedLabel(action) }}</p>
                    }
                    <p class="action__meta">
                      @if (action.status === 'DEMANDE') {
                        demandé{{ action.requestedTo ? ' à ' + action.requestedTo : '' }}{{ action.channel ? ' par ' + action.channel : '' }}
                        · {{ age(action.requestedAt || action.createdAt) }}
                        @if (action.followUpDue) { · <span class="action__late">à relancer</span> }
                      } @else if (isOpen(action)) {
                        @if (action.interlocuteurName || action.person) { <span class="action__person">{{ action.interlocuteurName || action.person }}</span> · }
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
                          @if (action.followUpDue) {
                            <button mat-flat-button type="button" class="action__followup"
                              matTooltip="Pré-remplit la saisie — rien n'est envoyé" (click)="relancer(action)">
                              <mat-icon>reply</mat-icon>Relancer
                            </button>
                          }
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

    .review {
      margin-bottom: var(--cg-space-3);
      padding: var(--cg-space-3);
      border: 1px solid var(--cg-accent);
      border-radius: 8px;
      background: var(--cg-surface-2);
    }

    .review__title {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: var(--cg-primary);
    }

    .review__lead,
    .review__where {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .review__row {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border-radius: 8px;
      background: var(--cg-surface);
    }

    .review__what {
      margin: 0;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .review__gestures,
    .review__all {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }

    .action__late {
      color: var(--cg-gold-ink);
      font-weight: 600;
    }

    .action__followup {
      --mdc-filled-button-container-color: var(--cg-accent);
    }

    .actions-panel__field--small {
      flex: 0 1 160px;
      min-width: 140px;
    }

    .action__tags {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1);
      margin: var(--cg-space-1) 0 0;
    }

    .tag {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-surface-2);
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .tag--late {
      background: var(--cg-gold-ink);
      color: var(--cg-surface);
      font-weight: 600;
    }

    .tag--moi {
      background: var(--cg-orange-2);
      color: var(--cg-navy);
      font-weight: 600;
    }

    .dups {
      margin-bottom: var(--cg-space-3);
      padding: var(--cg-space-3);
      border: 1px dashed var(--cg-accent);
      border-radius: 8px;
      background: var(--cg-surface-2);
    }

    .dups__title {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: var(--cg-primary);
    }

    .dups__row {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border-radius: 8px;
      background: var(--cg-surface);
    }

    .dups__keep,
    .dups__dup {
      margin: 0;
      font-size: 13px;
      overflow-wrap: anywhere;
    }

    .dups__keep {
      font-weight: 600;
    }

    .dups__reason {
      color: var(--cg-text-secondary);
      font-size: 12px;
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

  /** « Relancer » : le brouillon à déposer dans la zone de saisie, sans l'envoyer (F-175 / SF-175-06). */
  readonly followUp = output<string>();

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);

  readonly board = signal<TerminalActionBoard | null>(null);
  readonly scope = signal<AttentesScope>('here');
  /** La vue : les colonnes d'attentes, ou les personnes (F-180 / SF-180-03). */
  readonly view = signal<AttentesView>('columns');
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

  /** La reprise de l'existant (F-175 / SF-175-07) : chaque attente et l'état retenu (proposé d'abord). */
  readonly review = signal<ReviewRow[]>([]);
  readonly reviewHidden = signal(false);
  readonly reviewBusy = signal(false);

  /** Les doublons probables du poste (F-180 / SF-180-01). */
  readonly duplicates = signal<TerminalActionDuplicate[]>([]);

  /** F-180 / SF-180-02 : les natures et « qui agit ». */
  readonly categories = CATEGORIES;
  readonly quis = QUIS;
  newCategorie: TerminalActionCategorie | null = null;
  newQui: TerminalActionQui | null = null;
  /** L'échéance saisie à l'ajout, `aaaa-mm-jj` (F-180 / SF-180-06). */
  newDue = '';

  dueText(action: TerminalAction): string {
    return dueLabel(action);
  }
  readonly qualifyRows = signal<QualifyRow[]>([]);
  readonly qualifyHidden = signal(false);
  readonly qualifyBusy = signal(false);

  ngOnInit(): void {
    this.reload();
    this.loadReview();
    this.loadQualify();
  }

  categorieLabel(c: TerminalActionCategorie): string {
    return CATEGORIE_LABELS[c] ?? c;
  }

  quiLabel(q: TerminalActionQui): string {
    return QUI_LABELS[q] ?? q;
  }

  blockedLabel(action: TerminalAction): string {
    return blockedStepLabel(action);
  }

  private loadQualify(): void {
    this.service.qualify().subscribe({
      next: items => this.qualifyRows.set((items ?? []).map(item => ({
        item, categorie: item.suggestedCategorie, qui: item.suggestedQui,
      }))),
      error: () => this.qualifyRows.set([]),
    });
  }

  /** L'utilisateur change la nature ou « qui » retenus pour une attente. */
  chooseNature(actionId: string, categorie: TerminalActionCategorie, qui: TerminalActionQui): void {
    this.qualifyRows.update(rows => rows.map(r => (r.item.action.id === actionId ? { ...r, categorie, qui } : r)));
  }

  /** Valide une ou toutes les natures : seules celles envoyées sont écrites. */
  validateNature(rows: QualifyRow[]): void {
    if (!rows.length) {
      return;
    }
    this.qualifyBusy.set(true);
    const ids = new Set(rows.map(r => r.item.action.id));
    this.service.applyQualify(rows.map(r => ({ id: r.item.action.id, categorie: r.categorie, qui: r.qui })))
      .subscribe({
        next: () => {
          this.qualifyRows.update(list => list.filter(r => !ids.has(r.item.action.id)));
          this.qualifyBusy.set(false);
          this.reload();
        },
        error: () => {
          this.qualifyBusy.set(false);
          this.snackBar.open("La nature n'a pas pu être enregistrée.", 'Fermer', { duration: 5000 });
        },
      });
  }

  /** Les accès obtenus à proposer au registre (F-180 / SF-180-04). */
  readonly accesProposals = signal<AccesProposal[]>([]);

  private loadAccesProposals(): void {
    this.service.accesProposals(this.workspaceId()).subscribe({
      next: list => this.accesProposals.set(list ?? []),
      error: () => this.accesProposals.set([]),
    });
  }

  /** [Ajouter] : l'accès entre au registre avec ce que l'attente en dit. */
  addToRegistry(p: AccesProposal): void {
    this.settleProposal(p, this.service.addAcces(this.workspaceId(), {
      actionId: p.action.id, system: p.system, grantedBy: p.grantedBy, obtainedAt: p.obtainedAt,
    }));
  }

  /** [Pas besoin] : la proposition ne revient pas. */
  dismissRegistry(p: AccesProposal): void {
    this.settleProposal(p, this.service.dismissAccesProposal(this.workspaceId(), p.action.id));
  }

  private settleProposal(p: AccesProposal, call: Observable<unknown>): void {
    const before = this.accesProposals();
    this.busy.set(p.action.id);
    this.accesProposals.set(before.filter(x => x.action.id !== p.action.id));
    call.subscribe({
      next: () => this.busy.set(null),
      error: () => {
        this.accesProposals.set(before);
        this.fail();
      },
    });
  }

  private loadDuplicates(): void {
    this.service.duplicates(this.workspaceId()).subscribe({
      next: pairs => this.duplicates.set(pairs ?? []),
      // Silencieux : une proposition de fusion est un plus, son échec ne masque pas le tableau.
      error: () => this.duplicates.set([]),
    });
  }

  reasonLabel(pair: TerminalActionDuplicate): string {
    return DUPLICATE_REASON_LABELS[pair.reason] ?? '';
  }

  /** [Fusionner] : la paire disparaît tout de suite ; un échec la remet et le dit. */
  mergePair(pair: TerminalActionDuplicate): void {
    this.settlePair(pair, this.service.merge(pair.duplicate.workspaceId, pair.duplicate.id, pair.keep.id));
  }

  /** [Ce n'est pas pareil] : la paire n'est plus proposée. */
  notDuplicate(pair: TerminalActionDuplicate): void {
    this.settlePair(pair, this.service.notDuplicate(pair.duplicate.workspaceId, pair.duplicate.id));
  }

  private settlePair(pair: TerminalActionDuplicate, call: Observable<TerminalAction>): void {
    const before = this.duplicates();
    this.busy.set(pair.duplicate.id);
    this.duplicates.set(before.filter(p => p.duplicate.id !== pair.duplicate.id));
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.reload();
      },
      error: () => {
        this.duplicates.set(before);
        this.fail();
      },
    });
  }

  private loadReview(): void {
    this.service.review().subscribe({
      next: items => this.review.set((items ?? []).map(item => ({ item, choice: item.suggestedStatus }))),
      // Silencieux : la reprise est un passage unique, son échec ne doit pas masquer le tableau.
      error: () => this.review.set([]),
    });
  }

  /** L'utilisateur change l'état retenu pour une attente à vérifier. */
  choose(actionId: string, status: TerminalActionStatus): void {
    this.review.update(rows => rows.map(r => (r.item.action.id === actionId ? { ...r, choice: status } : r)));
  }

  /** Valide une ou toutes les lignes : seules les décisions envoyées changent quelque chose. */
  validate(rows: ReviewRow[]): void {
    if (!rows.length) {
      return;
    }
    this.reviewBusy.set(true);
    const ids = new Set(rows.map(r => r.item.action.id));
    this.service.applyReview(rows.map(r => ({ id: r.item.action.id, status: r.choice }))).subscribe({
      next: () => {
        this.review.update(list => list.filter(r => !ids.has(r.item.action.id)));
        this.reviewBusy.set(false);
        this.reload();
      },
      error: () => {
        this.reviewBusy.set(false);
        this.snackBar.open("La reprise n'a pas pu être enregistrée.", 'Fermer', { duration: 5000 });
      },
    });
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
        this.loadDuplicates();
        this.loadAccesProposals();
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

  /** Dépose le brouillon de relance dans la saisie du terminal — rien n'est envoyé. */
  relancer(action: TerminalAction): void {
    this.followUp.emit(followUpDraft(action));
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
    this.service.create(this.workspaceId(), {
      description, person: this.newPerson.trim() || null, categorie: this.newCategorie, qui: this.newQui,
      dueAt: this.newDue || null,
    })
      .subscribe({
        next: created => {
          this.patchBoard(b => ({ ...b, here: [...b.here, { ...created, workspaceName: null }] }));
          this.newDescription = '';
          this.newPerson = '';
          this.newCategorie = null;
          this.newQui = null;
          this.newDue = '';
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
