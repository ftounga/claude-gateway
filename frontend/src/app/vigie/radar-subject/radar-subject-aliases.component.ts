import { Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RadarAliasView } from '../../core/models/radar-subject.models';

/** Longueur maximale d'un alias, celle de la gateway (SF-99-03). */
export const ALIAS_MAX = 200;

/**
 * **Les autres noms d'un sujet** (F-99 / SF-99-06) : « aussi appelé », les consignes « n'est pas », et
 * l'ajout d'un alias. Composant de présentation : la page appelle la gateway et relit.
 *
 * <p>§17 : aucune couleur ; un nom est écrit, un geste est un bouton compact. Un sujet fusionné ne se
 * corrige plus : aucun geste n'y est proposé.</p>
 */
@Component({
  selector: 'app-radar-subject-aliases',
  imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatTooltipModule],
  template: `
    <div class="subject-aliases">
      @if (accepted().length > 0) {
        <div class="subject-aliases__row subject-aliases__accepted">
          <span class="subject-aliases__label">Aussi appelé</span>
          @for (alias of accepted(); track alias.id) {
            <span class="subject-aliases__name">
              {{ alias.alias }}
              @if (!locked()) {
                <button
                  mat-icon-button
                  type="button"
                  class="subject-aliases__remove"
                  [disabled]="busy()"
                  [attr.aria-label]="'Retirer cet alias : ' + alias.alias"
                  matTooltip="Retirer cet alias"
                  (click)="remove.emit(alias)"
                >
                  <mat-icon>close</mat-icon>
                </button>
              }
            </span>
          }
        </div>
      }
      @if (rejected().length > 0) {
        <div class="subject-aliases__row subject-aliases__rejected">
          <span class="subject-aliases__label">N'est pas</span>
          @for (alias of rejected(); track alias.id) {
            <span class="subject-aliases__name">
              {{ alias.alias }}
              @if (!locked()) {
                <button
                  mat-icon-button
                  type="button"
                  class="subject-aliases__remove"
                  [disabled]="busy()"
                  [attr.aria-label]="'Retirer cette consigne : ' + alias.alias"
                  matTooltip="Retirer cette consigne : le Radar pourra de nouveau rattacher ce nom à ce sujet"
                  (click)="remove.emit(alias)"
                >
                  <mat-icon>close</mat-icon>
                </button>
              }
            </span>
          }
        </div>
      }
      @if (!locked()) {
        @if (adding()) {
          <form class="subject-aliases__form" (submit)="submit($event)">
            <mat-form-field appearance="outline" class="subject-aliases__field" subscriptSizing="dynamic">
              <mat-label>Autre nom du sujet</mat-label>
              <input
                matInput
                class="subject-aliases__input"
                [attr.maxlength]="max"
                [value]="draft()"
                (input)="draft.set($any($event.target).value)"
                autocomplete="off"
              />
              <mat-hint align="end">{{ draft().length }} / {{ max }}</mat-hint>
            </mat-form-field>
            <button mat-stroked-button type="submit" class="subject-aliases__save" [disabled]="!canAdd()">Ajouter</button>
            <button mat-button type="button" class="subject-aliases__cancel" (click)="closeForm()">Annuler</button>
          </form>
        } @else {
          <button mat-button type="button" class="subject-aliases__open" [disabled]="busy()" (click)="adding.set(true)">
            <mat-icon>add</mat-icon>
            Ajouter un alias
          </button>
        }
      }
    </div>
  `,
  styles: `
    .subject-aliases {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
      margin-top: var(--cg-space-2);
    }

    .subject-aliases__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .subject-aliases__label {
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .subject-aliases__name {
      display: inline-flex;
      align-items: center;
      gap: 0;
      padding: 0 0 0 var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 16px;
      font-size: 13px;
    }

    .subject-aliases__rejected .subject-aliases__name {
      text-decoration: line-through;
      color: var(--cg-text-secondary);
    }

    .subject-aliases__remove {
      --mdc-icon-button-state-layer-size: 28px;
      width: 28px;
      height: 28px;
      padding: 0;

      mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }
    }

    .subject-aliases__form {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .subject-aliases__field {
      flex: 1 1 240px;
      max-width: 400px;
    }

    .subject-aliases__open {
      align-self: flex-start;
    }
  `,
})
export class RadarSubjectAliasesComponent {
  readonly aliases = input<RadarAliasView[]>([]);
  /** Sujet fusionné : il se lit, il ne se corrige plus. */
  readonly locked = input(false);
  /** Un geste est en cours : les boutons attendent. */
  readonly busy = input(false);

  readonly add = output<string>();
  readonly remove = output<RadarAliasView>();

  readonly max = ALIAS_MAX;
  readonly adding = signal(false);
  readonly draft = signal('');

  readonly accepted = computed(() => this.aliases().filter((alias) => !alias.rejected));
  readonly rejected = computed(() => this.aliases().filter((alias) => alias.rejected));
  readonly canAdd = computed(() => !this.busy() && this.draft().trim().length > 0
    && this.draft().trim().length <= ALIAS_MAX);

  submit(event: Event): void {
    event.preventDefault();
    if (!this.canAdd()) {
      return;
    }
    this.add.emit(this.draft().trim());
    this.closeForm();
  }

  closeForm(): void {
    this.adding.set(false);
    this.draft.set('');
  }
}
