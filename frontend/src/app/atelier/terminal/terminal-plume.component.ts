import { Component, input, output } from '@angular/core';

/** Ce que Plume montre au-dessus de la saisie (F-193 / SF-193-02). */
export type PlumeView =
  | { phase: 'running' }
  | { phase: 'enriched'; original: string; toConfirm: string[]; sources: string[] }
  | { phase: 'error'; message: string };

/** Les étapes de l'analyse, dans l'ordre où le serveur lit — les mêmes sources que l'agent. */
export const PLUME_STEPS = [
  'Passation du fil',
  'Plan du chantier',
  'STATE.md du sujet',
  'Carte du poste',
  'Fichiers touchés récemment',
  'Fil récent du sujet',
  'Rédaction de la demande',
] as const;

/**
 * **Plume** (F-193 / SF-193-02) — le panneau au-dessus de la saisie : l'analyse pendant
 * l'enrichissement, puis le rappel du texte d'origine, ce qui a été lu, ce qui reste **à confirmer**,
 * et « Revenir à mon texte ». Le panneau n'émet aucune requête et n'envoie rien.
 *
 * <p>Les étapes de l'analyse s'animent en CSS seul (aucune minuterie à nettoyer) ; la réduction des
 * animations les montre d'emblée.</p>
 */
@Component({
  selector: 'app-terminal-plume',
  standalone: true,
  template: `
    @switch (view().phase) {
      @case ('running') {
        <div class="plume plume--run" role="status" aria-live="polite">
          <h4 class="plume__title">Plume lit le contexte</h4>
          <ul class="plume__steps">
            @for (step of steps; track step; let i = $index) {
              <li class="plume__step" [style.--i]="i">{{ step }}</li>
            }
          </ul>
        </div>
      }
      @case ('enriched') {
        @if (enriched(); as e) {
          <section class="plume" aria-label="Demande enrichie par Plume">
            <header class="plume__head">
              <span class="plume__badge">enrichi par Plume</span>
              @if (e.sources.length > 0) {
                <span class="plume__src">Lu : {{ e.sources.join(' · ') }}</span>
              }
              <button type="button" class="plume__back" (click)="restore.emit()">Revenir à mon texte</button>
            </header>
            <p class="plume__orig">Votre texte : « {{ e.original }} »</p>
            @if (e.toConfirm.length > 0) {
              <div class="plume__ask" role="group" aria-label="À confirmer">
                <span class="plume__ask-label">À confirmer :</span>
                @for (item of e.toConfirm; track $index) {
                  <span class="plume__chip">{{ item }}</span>
                }
              </div>
            }
            <p class="plume__hint">Relisez et corrigez la demande ci-dessous. <kbd>Ctrl</kbd>+<kbd>Entrée</kbd> ou « Envoyer » pour l'envoyer.</p>
          </section>
        }
      }
      @case ('error') {
        @if (failure(); as f) {
          <div class="plume plume--error" role="alert">
            <span class="plume__msg">{{ f.message }}</span>
            <button type="button" class="plume__back" (click)="dismiss.emit()">Fermer</button>
          </div>
        }
      }
    }
  `,
  styles: `
    :host { display: block; }
    .plume {
      margin: 8px 0 4px; padding: 12px 16px; border-radius: 12px; display: grid; gap: 8px;
      background: var(--cg-navy-2); color: var(--cg-surface); font-family: var(--cg-font-body); font-size: 13px;
      border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent);
    }
    .plume--run { border-style: dashed; }
    .plume--error { grid-template-columns: minmax(0, 1fr) auto; align-items: center; border-color: var(--cg-error); }
    .plume__title { margin: 0; font: 600 13px var(--cg-font-heading); color: var(--cg-accent); }
    .plume__steps { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-family: var(--cg-font-mono); font-size: 12px; color: var(--cg-text-secondary); }
    .plume__step::before { content: '◌ '; color: var(--cg-accent); }
    @media (prefers-reduced-motion: no-preference) {
      .plume__step { animation: plume-step 0.6s ease-in both; animation-delay: calc(var(--i) * 0.6s); }
      @keyframes plume-step { from { opacity: 0.35; } to { opacity: 1; } }
    }
    .plume__head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .plume__badge {
      font: 500 11px var(--cg-font-mono); color: var(--cg-accent); padding: 2px 8px; border-radius: 999px;
      background: color-mix(in srgb, var(--cg-accent) 14%, transparent);
    }
    .plume__src { font-size: 12px; color: var(--cg-divider); min-width: 0; }
    .plume__back {
      margin-left: auto; background: none; color: var(--cg-surface); cursor: pointer; font: 600 12px var(--cg-font-body);
      border: 1px solid color-mix(in srgb, var(--cg-divider) 40%, transparent); border-radius: 8px; padding: 4px 12px; min-height: 32px;
    }
    .plume__back:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
    .plume__orig { margin: 0; padding: 4px 12px; border-left: 2px solid var(--cg-divider); color: var(--cg-divider); font-size: 12px; overflow-wrap: anywhere; }
    .plume__ask { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
    .plume__ask-label { font-size: 12px; color: var(--cg-divider); }
    .plume__chip {
      font-size: 12px; color: var(--cg-surface); border-radius: 4px; padding: 0 4px; overflow-wrap: anywhere;
      background: color-mix(in srgb, var(--cg-accent) 18%, transparent); border: 1px solid color-mix(in srgb, var(--cg-accent) 45%, transparent);
    }
    .plume__hint { margin: 0; font-size: 12px; color: var(--cg-text-secondary); }
    .plume__msg { overflow-wrap: anywhere; }
    kbd { font-family: var(--cg-font-mono); font-size: 11px; }
    @media (max-width: 819px) {
      .plume__back { margin-left: 0; min-height: 40px; }
    }
  `,
})
export class TerminalPlumeComponent {
  readonly view = input.required<PlumeView>();
  readonly restore = output<void>();
  readonly dismiss = output<void>();

  protected readonly steps = PLUME_STEPS;

  protected enriched(): Extract<PlumeView, { phase: 'enriched' }> | null {
    const v = this.view();
    return v.phase === 'enriched' ? v : null;
  }

  protected failure(): Extract<PlumeView, { phase: 'error' }> | null {
    const v = this.view();
    return v.phase === 'error' ? v : null;
  }
}
