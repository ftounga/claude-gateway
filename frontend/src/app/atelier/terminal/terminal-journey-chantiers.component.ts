import { Component, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { ClosedChantier, confidenceLabel, stepStatusLabel } from '../../core/models/journey.models';

/**
 * **Les chantiers clos du sujet** (F-176 / SF-176-11, décision D9) : un sujet vaste porte une suite de
 * chantiers ; ceux qui sont clos restent consultables — titre, dates, diagnostic, plan validé final.
 * Lecture seule.
 */
@Component({
  selector: 'app-terminal-journey-chantiers',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section class="chantiers" role="region" aria-label="Chantiers clos du sujet">
      <header class="chantiers__head">
        <strong>Chantiers clos</strong>
        <button mat-icon-button type="button" class="chantiers__close" aria-label="Fermer la liste des chantiers" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      @if (loading()) {
        <p class="chantiers__empty">Chargement…</p>
      } @else if (chantiers().length === 0) {
        <p class="chantiers__empty">Aucun chantier clos sur ce sujet.</p>
      } @else {
        <ol class="chantiers__list">
          @for (c of chantiers(); track c.number) {
            <li class="chantiers__item">
              <button type="button" class="chantiers__toggle" [attr.aria-expanded]="open() === c.number" (click)="toggle(c.number)">
                <mat-icon aria-hidden="true">{{ open() === c.number ? 'expand_less' : 'expand_more' }}</mat-icon>
                <span class="chantiers__title">Chantier {{ c.number }}{{ c.title ? ' — ' + c.title : '' }}</span>
                <span class="chantiers__dates">{{ date(c.openedAt) }} → {{ date(c.closedAt) }}</span>
              </button>
              @if (open() === c.number) {
                <div class="chantiers__detail">
                  @if (c.diagnosis) {
                    <p class="chantiers__line">Diagnostic (confiance {{ confidence(c.diagnosisConfidence) }}) : {{ c.diagnosis }}</p>
                  }
                  @if (c.plan.length > 0) {
                    <p class="chantiers__line">Plan{{ c.planVersion ? ' v' + c.planVersion : '' }} :</p>
                    <ol class="chantiers__steps">
                      @for (step of c.plan; track $index) {
                        <li>
                          <span class="chantiers__status" [attr.data-status]="step.status">{{ status(step.status) }}</span>
                          {{ step.title }}
                          @if (step.evidence) {
                            <span class="chantiers__evidence">— {{ step.evidence }}</span>
                          }
                        </li>
                      }
                    </ol>
                  } @else {
                    <p class="chantiers__line">Aucun plan n'avait été posé.</p>
                  }
                </div>
              }
            </li>
          }
        </ol>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .chantiers {
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      font-size: 13px;
    }

    .chantiers__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .chantiers__empty,
    .chantiers__line {
      margin: var(--cg-space-1) 0;
      color: var(--cg-text-secondary);
    }

    .chantiers__list,
    .chantiers__steps {
      margin: 0;
      padding-left: var(--cg-space-3);
    }

    .chantiers__list {
      list-style: none;
      padding-left: 0;
    }

    .chantiers__toggle {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-1) var(--cg-space-2);
      width: 100%;
      padding: var(--cg-space-1) 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .chantiers__title {
      font-weight: 600;
    }

    .chantiers__dates,
    .chantiers__evidence {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .chantiers__detail {
      padding-left: var(--cg-space-5);
    }

    .chantiers__status {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .chantiers__status[data-status='VERIFIE'],
    .chantiers__status[data-status='FAIT'] {
      color: var(--cg-success);
    }

    .chantiers__status[data-status='ECHEC'] {
      color: var(--cg-error);
    }
  `,
})
export class TerminalJourneyChantiersComponent {
  readonly chantiers = input<ClosedChantier[]>([]);
  readonly loading = input(false);
  readonly closed = output<void>();

  /** Le chantier déplié (un à la fois). */
  readonly open = signal<number | null>(null);

  toggle(number: number): void {
    this.open.set(this.open() === number ? null : number);
  }

  date(iso: string | null): string {
    if (!iso) {
      return '?';
    }
    const d = new Date(iso);
    return isNaN(d.getTime()) ? '?' : d.toLocaleDateString('fr-FR');
  }

  confidence(value: string | null): string {
    return confidenceLabel(value);
  }

  status(value: string): string {
    return stepStatusLabel(value);
  }
}
