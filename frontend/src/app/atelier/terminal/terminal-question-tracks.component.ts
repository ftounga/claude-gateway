import { Component, computed, input, output } from '@angular/core';

import { AtelierTerminalBlock, AtelierTurnQuestions } from '../../core/models/atelier.models';
import { MERGE_REQUEST, SPLIT_REQUEST, questionTracks, splitBannerText } from './question-tracks';

/**
 * **Deux questions, deux fils** (F-210 / SF-210-03) : le bandeau de découpage d'un tour et, pendant le
 * tour, une piste par question — libellé, état, dépendance, ses dernières actions.
 *
 * <p>« Fusionner » / « Séparer » émettent une demande explicite que le parent envoie par le chemin
 * normal (une précision si un tour tourne) : la correction est toujours visible, jamais silencieuse.</p>
 */
@Component({
  selector: 'app-terminal-question-tracks',
  standalone: true,
  template: `
    @if (questions(); as q) {
      <section class="qt" [attr.aria-label]="banner()">
        <header class="qt__band">
          @if (q.fusion; as fusion) {
            <span class="qt__title">Fusionnées</span>
            @if (fusion.raison) {
              <span class="qt__reason">{{ fusion.raison }}</span>
            }
            @if (!readOnly()) {
              <button type="button" class="qt__action" [disabled]="disabled()" (click)="request.emit(splitRequest)">
                Séparer
              </button>
            }
          } @else {
            <span class="qt__title">{{ banner() }}</span>
            @if (!readOnly()) {
              <button type="button" class="qt__action" [disabled]="disabled()" (click)="request.emit(mergeRequest)">
                Fusionner
              </button>
            }
          }
        </header>
        <ol class="qt__tracks">
          @for (track of tracks(); track track.question.id) {
            <li class="qt__track" [attr.data-state]="track.question.state">
              <span class="qt__num" aria-hidden="true">Q{{ track.question.id }}</span>
              <span class="qt__body">
                <span class="qt__label">{{ track.question.libelle }}</span>
                <span class="qt__meta">
                  <span class="qt__state">{{ track.stateLabel }}</span>
                  @if (track.waitingOn) {
                    <span class="qt__wait">· {{ track.waitingOn }}</span>
                  }
                  @if (track.actionCount > 0) {
                    <span class="qt__count">· {{ track.actionCount }} action{{ track.actionCount > 1 ? 's' : '' }}</span>
                  }
                </span>
                @if (live()) {
                  @for (action of track.recent; track $index) {
                    <code class="qt__step">{{ action }}</code>
                  }
                }
              </span>
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .qt {
      margin: 4px 0 8px; background: var(--cg-navy-2); color: #fff; font-family: 'Inter', sans-serif;
      border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent); border-radius: 12px; overflow: hidden;
    }
    .qt__band {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
    .qt__title { font: 600 14px 'Inter', sans-serif; }
    .qt__reason { font-size: 12px; color: #9FB0C4; min-width: 0; overflow-wrap: anywhere; }
    .qt__action {
      margin-left: auto; padding: 4px 8px; background: none; cursor: pointer;
      border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent); border-radius: 4px;
      color: var(--cg-accent); font: 500 12px 'Inter', sans-serif;
    }
    .qt__action:hover:not(:disabled) { background: color-mix(in srgb, var(--cg-accent) 8%, transparent); }
    .qt__action:disabled { opacity: .5; cursor: default; }
    .qt__action:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
    .qt__tracks { list-style: none; margin: 0; padding: 0; display: grid; }
    .qt__track {
      display: grid; grid-template-columns: 32px minmax(0, 1fr); gap: 12px; align-items: start; padding: 8px 12px;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
    .qt__track:last-child { border-bottom: 0; }
    .qt__track[data-state='EN_COURS'] { box-shadow: inset 3px 0 0 var(--cg-accent); }
    .qt__num {
      height: 24px; border-radius: 4px; display: grid; place-items: center;
      background: rgba(255, 255, 255, .07); border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
      font: 600 12px 'JetBrains Mono', monospace;
    }
    .qt__body { display: grid; gap: 4px; min-width: 0; }
    .qt__label { font-weight: 600; font-size: 14px; overflow-wrap: anywhere; }
    .qt__meta { font-size: 12px; color: #9FB0C4; }
    .qt__track[data-state='EN_COURS'] .qt__state { color: var(--cg-accent); }
    .qt__track[data-state='TERMINEE'] .qt__state { color: #5FD08F; }
    .qt__track[data-state='NON_ABOUTIE'] .qt__state { color: #F9A825; }
    .qt__step {
      font: 12px 'JetBrains Mono', monospace; color: var(--cg-divider);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
    }
  `,
})
export class TerminalQuestionTracksComponent {
  /** Les questions du tour ; `null` ⇒ rien n'est rendu. */
  readonly questions = input<AtelierTurnQuestions | null | undefined>(null);
  /** Les blocs du tour, pour rattacher les actions à leur piste. */
  readonly blocks = input<AtelierTerminalBlock[]>([]);
  /** Vrai pendant le tour : les dernières actions de chaque piste sont montrées. */
  readonly live = input(false);
  /** Lecture seule : pas de bouton. */
  readonly readOnly = input(false);
  /** Un envoi n'est pas possible à l'instant. */
  readonly disabled = input(false);
  /** La demande à envoyer (Fusionner / Séparer). */
  readonly request = output<string>();

  readonly mergeRequest = MERGE_REQUEST;
  readonly splitRequest = SPLIT_REQUEST;

  readonly tracks = computed(() => questionTracks(this.questions(), this.blocks()));
  readonly banner = computed(() => {
    const q = this.questions();
    return q ? splitBannerText(q) : '';
  });
}
