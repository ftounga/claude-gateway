import { Component, ElementRef, HostListener, computed, effect, inject, input, output, signal } from '@angular/core';

import { AtelierNextStep, AtelierNextStepLabel } from '../../core/models/atelier.models';

/** Options affichées au plus — et touches 1 à 4. */
export const MAX_NEXT_STEPS = 4;

/** L'étiquette, dite en français. Inconnue ⇒ « Continuer ». */
const LABELS: Record<AtelierNextStepLabel, string> = {
  VERIFIER: 'Vérifier',
  CONTINUER: 'Continuer',
  LIVRER: 'Livrer',
  RISQUE: 'Risque',
};

export function nextStepLabel(label: string | null | undefined): string {
  return LABELS[label as AtelierNextStepLabel] ?? LABELS.CONTINUER;
}

/** La classe de couleur de l'étiquette. */
export function nextStepTone(label: string | null | undefined): string {
  return label && label in LABELS ? label.toLowerCase() : 'continuer';
}

/** Une cible où l'on écrit : une touche y tapée appartient au champ, pas au panneau. */
function isEditable(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element || typeof element.tagName !== 'string') {
    return false;
  }
  const tag = element.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || element.isContentEditable === true;
}

/**
 * **« Et maintenant ? »** (F-192 / SF-192-02) — les suites proposées par l'agent principal, à la fin
 * de sa réponse. Un clic ou une touche 1-4 **remplit** la saisie : rien ne part sans un geste.
 *
 * <p>Se replie sur une ligne « N suites proposées » dès qu'on écrit, ou sur « Masquer » / Échap.
 * Le panneau n'émet aucune requête : tout ce qu'il montre est arrivé avec la réponse.</p>
 */
@Component({
  selector: 'app-terminal-next-steps',
  standalone: true,
  template: `
    @if (shown().length > 0) {
      @if (collapsed()) {
        <div class="cap cap--folded" role="group" aria-label="Suites proposées">
          <span class="cap__count">{{ shown().length }} suite{{ shown().length > 1 ? 's' : '' }} proposée{{ shown().length > 1 ? 's' : '' }}</span>
          <button type="button" class="cap__toggle" (click)="reveal()">Afficher</button>
        </div>
      } @else {
        <section class="cap" aria-label="Et maintenant ?">
          <header class="cap__head">
            <h3 class="cap__title">Et maintenant ?</h3>
            <span class="cap__src">proposé par l'agent, d'après ce tour</span>
            <button type="button" class="cap__toggle" (click)="hide()">Masquer</button>
          </header>
          <div class="cap__opts">
            @for (step of shown(); track $index; let i = $index) {
              <button
                type="button"
                class="opt"
                [class.opt--sel]="selected() === i"
                [attr.aria-label]="'Option ' + (i + 1) + ' : ' + step.title"
                (click)="choose(i)"
              >
                <span class="key" aria-hidden="true">{{ i + 1 }}</span>
                <span class="body">
                  <span class="t">{{ step.title }}</span>
                  <span class="why">{{ step.why }}</span>
                </span>
                <span class="tag" [attr.data-tone]="tone(step.label)">{{ label(step.label) }}</span>
              </button>
            }
            <button type="button" class="opt opt--other" (click)="other.emit()">
              <span class="key" aria-hidden="true">…</span>
              <span class="body"><span class="why">Autre chose : tapez votre suite</span></span>
            </button>
          </div>
          <footer class="cap__foot">
            <span><kbd>1</kbd>–<kbd>{{ shown().length }}</kbd> choisir</span>
            <span><kbd>Échap</kbd> masquer</span>
            <span>Rien n'est envoyé sans vous</span>
          </footer>
        </section>
      }
    }
  `,
  styles: `
    :host { display: block; }
    .cap {
      margin: 4px 0 8px; background: var(--cg-navy-2); color: #fff; font-family: 'Inter', sans-serif;
      border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent); border-radius: 12px; overflow: hidden;
    }
    .cap--folded { display: flex; align-items: center; gap: 8px; padding: 8px 12px; font-size: 13px; }
    .cap__count { color: var(--cg-divider); }
    .cap__head {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
    .cap__title { margin: 0; font: 600 14px 'Inter', sans-serif; }
    .cap__src { font-size: 12px; color: #9FB0C4; }
    .cap__toggle {
      margin-left: auto; background: none; border: 0; color: #9FB0C4; cursor: pointer;
      font: 12px 'Inter', sans-serif; padding: 4px 8px; border-radius: 4px;
    }
    .cap__toggle:hover { color: #fff; }
    .cap__opts { display: grid; }
    .opt {
      display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; gap: 12px; align-items: start;
      width: 100%; padding: 12px; background: none; border: 0; color: inherit; text-align: left;
      font: inherit; cursor: pointer; border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
    .opt:hover, .opt--sel { background: color-mix(in srgb, var(--cg-accent) 8%, transparent); }
    .opt--sel { box-shadow: inset 3px 0 0 var(--cg-accent); }
    .opt--other { grid-template-columns: 28px minmax(0, 1fr); }
    .opt:focus-visible, .cap__toggle:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: -2px; }
    .key {
      width: 24px; height: 24px; border-radius: 4px; display: grid; place-items: center;
      background: rgba(255, 255, 255, .07); border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
      font: 600 12px 'JetBrains Mono', monospace;
    }
    .body { display: grid; gap: 4px; min-width: 0; }
    .t { font-weight: 600; font-size: 14px; }
    .why { font-size: 12px; color: #9FB0C4; line-height: 1.45; }
    .tag { font: 500 11px 'JetBrains Mono', monospace; padding: 2px 8px; border-radius: 999px; white-space: nowrap; }
    .tag[data-tone='verifier'] { color: #5FD08F; background: color-mix(in srgb, var(--cg-success) 14%, transparent); }
    .tag[data-tone='continuer'] { color: var(--cg-accent); background: color-mix(in srgb, var(--cg-accent) 14%, transparent); }
    .tag[data-tone='livrer'] { color: var(--cg-orange-2); background: color-mix(in srgb, var(--cg-orange-2) 14%, transparent); }
    .tag[data-tone='risque'] { color: #F9A825; background: color-mix(in srgb, #F9A825 14%, transparent); }
    .cap__foot { display: flex; gap: 16px; flex-wrap: wrap; padding: 8px 12px; font-size: 12px; color: #9FB0C4; }
    kbd {
      font: 11px 'JetBrains Mono', monospace; padding: 0 4px; border-radius: 4px;
      border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); color: var(--cg-divider);
    }
    @media (max-width: 600px) {
      .opt { grid-template-columns: 28px minmax(0, 1fr); }
      .opt .tag { grid-column: 2; justify-self: start; }
    }
  `,
})
export class TerminalNextStepsComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** Les suites du dernier tour ; vide ⇒ rien n'est rendu. */
  readonly steps = input<AtelierNextStep[]>([]);
  /** La saisie courante : non vide ⇒ le panneau se replie. */
  readonly draft = input<string>('');
  /** Identité du tour (id du message) : un nouveau tour rouvre le panneau. */
  readonly turnKey = input<string | null>(null);

  /** Une option choisie : sa demande, à poser dans la saisie — sans envoi. */
  readonly pick = output<string>();
  /** « Autre chose » : donner la main à la saisie. */
  readonly other = output<void>();

  readonly shown = computed(() => (this.steps() ?? []).slice(0, MAX_NEXT_STEPS));
  readonly hidden = signal(false);
  readonly selected = signal<number | null>(null);
  readonly collapsed = computed(() => this.hidden() || (this.draft() ?? '').trim().length > 0);

  constructor() {
    // Un nouveau tour : panneau rouvert, rien de sélectionné.
    effect(() => {
      this.turnKey();
      this.hidden.set(false);
      this.selected.set(null);
    });
  }

  label(value: string): string {
    return nextStepLabel(value);
  }

  tone(value: string): string {
    return nextStepTone(value);
  }

  choose(index: number): void {
    const step = this.shown()[index];
    if (!step) {
      return;
    }
    this.selected.set(index);
    this.pick.emit(step.request);
  }

  hide(): void {
    this.hidden.set(true);
  }

  reveal(): void {
    this.hidden.set(false);
  }

  /**
   * Touches 1-4 et Échap — seulement hors d'un champ de saisie (une touche tapée dans la saisie lui
   * appartient), panneau déplié, et quand le focus est dans ce terminal ou nulle part.
   */
  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (this.shown().length === 0 || this.collapsed() || event.ctrlKey || event.metaKey || event.altKey
        || isEditable(event.target) || !this.focusIsHere()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.hide();
      return;
    }
    const index = Number.parseInt(event.key, 10) - 1;
    if (Number.isInteger(index) && index >= 0 && index < this.shown().length && event.key.length === 1) {
      event.preventDefault();
      this.choose(index);
    }
  }

  /** Le focus est-il dans le terminal qui porte ce panneau (ou nulle part) ? */
  private focusIsHere(): boolean {
    const active = document.activeElement;
    if (!active || active === document.body) {
      return true;
    }
    const element = this.host.nativeElement as HTMLElement;
    const terminal = element.closest('app-atelier-terminal') ?? element;
    return terminal.contains(active);
  }
}
