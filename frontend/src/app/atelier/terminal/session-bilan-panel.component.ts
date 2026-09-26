import { Component, HostListener, computed, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierBilanReport } from '../../core/models/atelier.models';

/** L'axe d'une suggestion, dit en français plutôt qu'en constante. */
export function axisLabel(axis: string): string {
  switch (axis) {
    case 'COUT':
      return 'Coût';
    case 'TEMPS':
      return 'Temps';
    case 'RAISONNEMENT':
      return 'Raisonnement';
    default:
      return axis;
  }
}

/** La durée d'une session : des minutes seules ne se lisent plus passé une heure. */
export function elapsedLabel(minutes: number): string {
  if (minutes < 60) {
    return `${Math.max(0, Math.round(minutes))} min`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

/**
 * **Le bilan de la session qui se ferme** (F-155 / SF-155-07), montré là où le geste a lieu.
 *
 * <p>Il existait déjà, entièrement calculé, mais seul l'écran d'administration le rendait : le PO a
 * cliqué « nouveau départ » sur une session à 152 € et **n'a rien vu**. Une chaîne serveur complète
 * n'est pas une feature livrée tant qu'aucun écran ne la rend.</p>
 *
 * <p>Un panneau, pas un bandeau : trois suggestions ne tiennent pas dans une ligne, et un bandeau
 * s'efface tout seul — or c'est précisément ce qu'on veut donner le temps de lire.</p>
 */
@Component({
  selector: 'app-session-bilan-panel',
  imports: [DecimalPipe, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <aside class="bilan-panel" role="complementary" aria-label="Bilan de la session">
      <header class="bilan-panel__bar">
        <h2 class="bilan-panel__title">Bilan de la session</h2>
        <span class="bilan-panel__spacer"></span>
        <button mat-icon-button type="button" matTooltip="Fermer (Échap)"
          aria-label="Fermer le bilan de session" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>

      <div class="bilan-panel__content">
        <p class="bilan-panel__lede">
          @if (report().kept) {
            Cette session méritait d'être relevée : elle est gardée, et se comparera aux suivantes.
          } @else {
            Session modeste — le relevé est montré, mais n'est pas gardé.
          }
        </p>

        <dl class="figures">
          <div class="figure">
            <dt>Coût</dt>
            <dd class="figure__value">{{ report().costEur | number: '1.2-2' }} €</dd>
          </div>
          <div class="figure">
            <dt>Tours</dt>
            <dd class="figure__value">{{ report().turns }}</dd>
          </div>
          <div class="figure">
            <dt>Durée</dt>
            <dd class="figure__value">{{ elapsed() }}</dd>
          </div>
          <div class="figure">
            <dt>Part de cache</dt>
            <dd class="figure__value">{{ report().cacheShare }} %</dd>
          </div>
        </dl>

        <p class="bilan-panel__meta">
          {{ report().toolCalls }} appels d'outils@if (report().failedTools > 0) {,
            <strong>{{ report().failedTools }} en échec</strong>}@if (report().filesWritten > 0) {,
            {{ report().filesWritten }} fichiers écrits}@if (report().model) {
            · {{ report().model }}}
        </p>

        @if (!report().suggestions.length) {
          <p class="bilan-panel__empty">Rien à signaler sur cette session.</p>
        }

        @for (suggestion of report().suggestions; track suggestion.kind) {
          <article class="suggestion">
            <p class="suggestion__axis">{{ axis(suggestion.axis) }}</p>
            <p class="suggestion__advice">{{ suggestion.advice }}</p>
            <!-- LA MESURE VOYAGE AVEC LE CONSEIL : sans elle, ce ne serait qu'un avis, et un avis
                 ne se vérifie pas (F-155 / SF-155-02). -->
            <p class="suggestion__measure">{{ suggestion.measure }}</p>
            <p class="suggestion__gain">
              Gain estimé : {{ suggestion.gainPct }} %@if (suggestion.gainEur != null) {
                · {{ suggestion.gainEur | number: '1.2-2' }} €}
            </p>
          </article>
        }

        @if (report().discarded > 0) {
          <!-- Les suggestions écartées sont DITES, jamais tues : leur nombre explique pourquoi la
               liste est courte, et empêche de croire que rien n'a été cherché. -->
          <p class="bilan-panel__discarded">
            {{ report().discarded }} suggestion(s) écartée(s) : impact trop faible pour valoir un geste.
          </p>
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

    .bilan-panel {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--cg-surface);
      border-left: 1px solid var(--cg-divider);
      /* ÎLOT CLAIR posé sur le terminal sombre, qui redéfinit l'encre des boutons-icônes pour une
         surface sombre (F-30 / SF-30-15) : sans ce rappel, Fermer serait clair sur blanc. */
      --mdc-icon-button-icon-color: var(--cg-text-secondary);
      color: var(--cg-text);
    }

    .bilan-panel__bar {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2) var(--cg-space-1) var(--cg-space-3);
      border-bottom: 1px solid var(--cg-divider);
    }

    .bilan-panel__title {
      margin: 0;
      font-size: 18px;
      font-weight: 600;
    }

    .bilan-panel__spacer {
      flex: 1;
    }

    .bilan-panel__content {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: var(--cg-space-2);
    }

    .bilan-panel__lede {
      margin: 0 0 var(--cg-space-2);
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .figures {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-2);
    }

    .figure {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      min-width: 0;
    }

    .figure dt {
      margin: 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .figure__value {
      margin: var(--cg-space-1) 0 0;
      font-size: 20px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .bilan-panel__meta,
    .bilan-panel__empty,
    .bilan-panel__discarded {
      margin: 0 0 var(--cg-space-2);
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .suggestion {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
    }

    .suggestion__axis {
      margin: 0;
      font-size: 11px;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--cg-text-secondary);
    }

    .suggestion__advice {
      margin: var(--cg-space-1) 0 0;
      font-weight: 600;
    }

    .suggestion__measure,
    .suggestion__gain {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class SessionBilanPanelComponent {

  readonly report = input.required<AtelierBilanReport>();
  readonly closed = output<void>();

  readonly elapsed = computed(() => elapsedLabel(this.report().elapsedMinutes));

  axis(value: string): string {
    return axisLabel(value);
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }
}
