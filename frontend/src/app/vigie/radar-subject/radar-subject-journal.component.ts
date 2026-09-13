import { Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarCommitmentView, RadarCorrectionView } from '../../core/models/radar-subject.models';
import { CorrectionLine, correctionLine } from './radar-subject-journal';
import { whenLabel } from './radar-subject-view';

/**
 * **Vos corrections sur ce sujet** (F-99 / SF-99-06) — la part de la chronologie écrite par
 * l'utilisateur : chaque geste souverain en mots, son moment, et *Annuler*. Composant de présentation :
 * la page lit le journal, annule et relit.
 */
@Component({
  selector: 'app-radar-subject-journal',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <section class="subject-journal" aria-labelledby="radar-subject-journal">
      <h3 id="radar-subject-journal" class="subject-journal__h">Vos corrections sur ce sujet</h3>
      @if (corrections(); as list) {
        @if (list === 'error') {
          <p class="subject-journal__empty subject-journal__error">Vos corrections n'ont pas pu être lues.</p>
        } @else if (list.length === 0) {
          <p class="subject-journal__empty subject-journal__none">Aucune correction : ce que dit la page vient des sources.</p>
        } @else {
          <ul class="subject-journal__list">
            @for (item of lines(); track item.correction.id) {
              <li class="subject-journal__item" [class.subject-journal__item--undone]="item.correction.undoneAt !== null"
                [attr.data-action]="item.correction.action">
                <mat-icon class="subject-journal__icon" aria-hidden="true">edit_note</mat-icon>
                <span class="subject-journal__text">
                  <span class="subject-journal__what">{{ item.line.text }}</span>
                  <span class="subject-journal__when">{{ whenLabel(item.correction.createdAt) }}</span>
                  @if (item.line.linkSubjectId && item.correction.undoneAt === null) {
                    <a class="subject-journal__link" [routerLink]="['/vigie', hostRef(), 'sujets', item.line.linkSubjectId]">
                      {{ item.line.linkLabel }}
                    </a>
                  }
                </span>
                @if (item.correction.undoneAt === null) {
                  <button
                    mat-button
                    type="button"
                    class="subject-journal__undo"
                    [disabled]="undoingId() !== null"
                    (click)="undo.emit(item.correction)"
                  >
                    @if (undoingId() === item.correction.id) {
                      <mat-spinner diameter="16"></mat-spinner>
                    } @else {
                      <mat-icon>undo</mat-icon>
                    }
                    Annuler
                  </button>
                } @else {
                  <span class="badge badge--neutral subject-journal__undone">annulé</span>
                }
              </li>
            }
          </ul>
        }
      } @else {
        <div class="subject-journal__loading"><mat-spinner diameter="20"></mat-spinner></div>
      }
    </section>
  `,
  styles: `
    .subject-journal {
      margin-top: var(--cg-space-3);
    }

    .subject-journal__h {
      margin: 0 0 var(--cg-space-2);
      font-size: 14px;
      font-weight: 600;
    }

    .subject-journal__empty {
      margin: 0;
      color: var(--cg-text-secondary);
      font-size: 14px;
    }

    .subject-journal__list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
    }

    .subject-journal__item {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) 0;
      border-bottom: 1px solid var(--cg-divider);
    }

    .subject-journal__item--undone .subject-journal__what {
      color: var(--cg-text-secondary);
      text-decoration: line-through;
    }

    .subject-journal__icon {
      color: var(--cg-text-secondary);
      flex: none;
    }

    .subject-journal__text {
      flex: 1 1 200px;
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--cg-space-2);
      min-width: 0;
    }

    .subject-journal__when {
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .subject-journal__link {
      font-size: 13px;
    }

    .subject-journal__loading {
      display: flex;
      padding: var(--cg-space-2) 0;
    }
  `,
})
export class RadarSubjectJournalComponent {
  readonly hostRef = input<string | null>(null);
  /** Le journal : `null` en lecture, `'error'` s'il n'a pas pu être lu. */
  readonly corrections = input<RadarCorrectionView[] | 'error' | null>(null);
  readonly commitments = input<RadarCommitmentView[]>([]);
  /** La correction en cours d'annulation. */
  readonly undoingId = input<string | null>(null);

  readonly undo = output<RadarCorrectionView>();

  readonly whenLabel = whenLabel;

  readonly lines = computed<{ correction: RadarCorrectionView; line: CorrectionLine }[]>(() => {
    const list = this.corrections();
    if (!Array.isArray(list)) {
      return [];
    }
    const names = new Map(this.commitments().map((c) => [c.id, c.description] as const));
    return list.map((correction) => ({ correction, line: correctionLine(correction, names) }));
  });
}
