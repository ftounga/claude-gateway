import { Component, ElementRef, HostListener, computed, effect, inject, input, output, signal } from '@angular/core';

import { AtelierNextStep, AtelierNextStepLabel, AtelierNextStepsChantier } from '../../core/models/atelier.models';

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

/**
 * L'étiquette de rattachement d'une suite au chantier (F-196 / SF-196-02), ou `null` hors chantier
 * ouvert : « Étape N · titre » pour une étape du plan, « Hors plan · approfondissement » sinon.
 */
export function nextStepScopeLabel(step: AtelierNextStep, chantierOpen: boolean, focus: number | null = null): string | null {
  if (!chantierOpen || !step.scope) {
    return null;
  }
  if (step.scope === 'APPROFONDISSEMENT') {
    return 'Hors plan · approfondissement';
  }
  // F-206 / SF-206-04 : une suite d'un AUTRE chantier ouvert que le focus le dit.
  const other = nextStepOtherChantier(step, chantierOpen, focus);
  if (other !== null) {
    const step_ = step.stepIndex ? ` · Étape ${step.stepIndex}` : '';
    const title = step.stepIndex && step.stepTitle ? ` · ${step.stepTitle}` : '';
    return `Chantier ${other}${step_}${title}`;
  }
  if (step.scope === 'CHANTIER' && step.stepIndex) {
    return step.stepTitle ? `Étape ${step.stepIndex} · ${step.stepTitle}` : `Étape ${step.stepIndex}`;
  }
  return null;
}

/**
 * Le chantier ouvert, autre que le focus, auquel une suite se rattache (F-206 / SF-206-04) — celui sur
 * lequel « Basculer » mène —, ou `null` (suite du focus, approfondissement, hors chantier).
 */
export function nextStepOtherChantier(step: AtelierNextStep, chantierOpen: boolean, focus: number | null): number | null {
  if (!chantierOpen || step.scope !== 'CHANTIER' || !step.chantier || focus === null) {
    return null;
  }
  return step.chantier !== focus ? step.chantier : null;
}

/** « Chantier : 3/10 étapes », ou, avec plusieurs chantiers, « Chantier 2 : 3/10 · Chantier 1 : 1/2 ». */
export function chantierProgressText(c: AtelierNextStepsChantier, focus: number | null = null): string {
  const others = c.others ?? [];
  if (others.length === 0) {
    return `Chantier : ${c.done}/${c.total} étape${c.total > 1 ? 's' : ''}`;
  }
  const all = [c, ...others];
  const first = focus ?? c.number;
  all.sort((a, b) => (a.number === first ? -1 : b.number === first ? 1 : a.number - b.number));
  return all.filter((x) => x.total > 0).map((x) => `Chantier ${x.number} : ${x.done}/${x.total}`).join(' · ');
}

/** « il reste 7 étapes » / « il reste 1 étape ». */
export function remainingStepsText(remaining: number): string {
  return `Le chantier n'est pas terminé : il reste ${remaining} étape${remaining > 1 ? 's' : ''}`;
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
 * La saisie composée des suites cochées (SF-192-04) : une seule demande, telle quelle ; plusieurs,
 * en une seule demande qui les fait traiter en parallèle quand elles sont indépendantes (F-195 /
 * SF-195-01), dans l'ordre d'affichage — sur une ligne, le champ de saisie étant mono-ligne.
 */
export function composeNextSteps(requests: string[]): string {
  if (requests.length <= 1) {
    return requests[0] ?? '';
  }
  return 'Traite ces suites — en parallèle celles qui sont indépendantes : ' + requests.map((r, i) => `${i + 1}) ${r.trim()}`).join(' ; ');
}

/**
 * **« Et maintenant ? »** (F-192 / SF-192-02) — les suites proposées par l'agent principal, à la fin
 * de sa réponse. Un clic ou une touche 1-4 **coche** une suite (SF-192-04 : plusieurs à la fois) et
 * **remplit** la saisie de leur enchaînement : rien ne part sans un geste.
 *
 * <p>Reste ouvert tant que la saisie vaut le texte qu'il a posé ; se replie sur une ligne
 * « N suites proposées » dès qu'on écrit autre chose, ou sur « Masquer » / Échap.
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
            @if (chantier(); as c) {
              <span class="cap__chantier">{{ progressText(c) }}</span>
            }
            @if (checked().length > 0) {
              <span class="cap__checked">{{ checked().length }} cochée{{ checked().length > 1 ? 's' : '' }}</span>
            }
            <button type="button" class="cap__toggle" (click)="hide()">Masquer</button>
          </header>
          <div class="cap__opts">
            @for (step of shown(); track $index; let i = $index) {
              <button
                type="button"
                class="opt"
                role="checkbox"
                [class.opt--sel]="isChecked(i)"
                [attr.aria-checked]="isChecked(i)"
                [attr.aria-label]="'Option ' + (i + 1) + ' : ' + step.title"
                (click)="toggle(i)"
              >
                <span class="key" aria-hidden="true">
                  <span class="box" [class.box--on]="isChecked(i)">@if (isChecked(i)) {✓}</span>{{ i + 1 }}
                </span>
                <span class="body">
                  <span class="t">{{ step.title }}</span>
                  @if (scopeLabel(step); as scope) {
                    <span class="scope" [attr.data-scope]="step.scope">{{ scope }}</span>
                  }
                  <span class="why">{{ step.why }}</span>
                </span>
                <span class="tag" [attr.data-tone]="tone(step.label)">{{ label(step.label) }}</span>
              </button>
              @if (otherChantier(step); as n) {
                <button type="button" class="switch" (click)="switchChantier.emit(n)">Basculer sur le chantier {{ n }}</button>
              }
            }
            <button type="button" class="opt opt--other" (click)="other.emit()">
              <span class="key" aria-hidden="true">…</span>
              <span class="body"><span class="why">Autre chose : tapez votre suite</span></span>
            </button>
          </div>
          @if (remainingWarning(); as warning) {
            <p class="cap__warn" role="status">{{ warning }}</p>
          }
          <footer class="cap__foot">
            <span><kbd>1</kbd>–<kbd>{{ shown().length }}</kbd> cocher (plusieurs possibles)</span>
            @if (checked().length > 0) {
              <span><kbd>Entrée</kbd> aller à la saisie</span>
            }
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
    .cap__checked { font-size: 12px; color: var(--cg-accent); }
    .cap__chantier { font: 500 12px 'Inter', sans-serif; color: var(--cg-divider); }
    .scope {
      justify-self: start; font: 500 12px 'Inter', sans-serif; padding: 0 8px; border-radius: 4px;
      color: #9FB0C4; border: 1px solid color-mix(in srgb, var(--cg-divider) 30%, transparent);
      max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .scope[data-scope='APPROFONDISSEMENT'] { color: #F9A825; border-color: #F9A825; }
    .cap__warn {
      margin: 0; padding: 8px 12px; font-size: 12px; color: #F9A825;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
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
      width: 24px; height: 24px; border-radius: 4px; display: grid; place-items: center; position: relative;
      background: rgba(255, 255, 255, .07); border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
      font: 600 12px 'JetBrains Mono', monospace;
    }
    .box {
      position: absolute; top: -6px; left: -6px; width: 14px; height: 14px; border-radius: 3px;
      display: grid; place-items: center; font-size: 10px; line-height: 1; color: var(--cg-navy-2);
      background: var(--cg-navy-2); border: 1px solid var(--cg-divider);
    }
    .box--on { background: var(--cg-accent); border-color: var(--cg-accent); }
    .body { display: grid; gap: 4px; min-width: 0; }
    .t { font-weight: 600; font-size: 14px; }
    .why { font-size: 12px; color: #9FB0C4; line-height: 1.45; }
    .tag { font: 500 11px 'JetBrains Mono', monospace; padding: 2px 8px; border-radius: 999px; white-space: nowrap; }
    .tag[data-tone='verifier'] { color: #5FD08F; background: color-mix(in srgb, var(--cg-success) 14%, transparent); }
    .tag[data-tone='continuer'] { color: var(--cg-accent); background: color-mix(in srgb, var(--cg-accent) 14%, transparent); }
    .tag[data-tone='livrer'] { color: var(--cg-orange-2); background: color-mix(in srgb, var(--cg-orange-2) 14%, transparent); }
    .tag[data-tone='risque'] { color: #F9A825; background: color-mix(in srgb, #F9A825 14%, transparent); }
    .switch {
      justify-self: start; margin: -8px 0 8px 52px; padding: 4px 8px; background: none; cursor: pointer;
      border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent); border-radius: 4px;
      color: var(--cg-accent); font: 500 12px 'Inter', sans-serif;
    }
    .switch:hover { background: color-mix(in srgb, var(--cg-accent) 8%, transparent); }
    .switch:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
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
  /**
   * L'avancement du chantier guidé ouvert (F-196 / SF-196-02) ; `null` ⇒ panneau F-192 inchangé
   * (aucune étiquette de rattachement, aucun avertissement).
   */
  readonly chantier = input<AtelierNextStepsChantier | null>(null);
  /**
   * Le chantier au focus du parcours en direct (F-206 / SF-206-04) ; `null` ⇒ celui de la réponse.
   * Après une bascule, les libellés suivent sans nouveau tour.
   */
  readonly focus = input<number | null>(null);
  /** La saisie courante : non vide ⇒ le panneau se replie. */
  readonly draft = input<string>('');
  /** Identité du tour (id du message) : un nouveau tour rouvre le panneau. */
  readonly turnKey = input<string | null>(null);

  /** Les options cochées : leur enchaînement, à poser dans la saisie — sans envoi (vide ⇒ tout décoché). */
  readonly pick = output<string>();
  /** « Autre chose », ou Entrée après avoir coché : donner la main à la saisie. */
  readonly other = output<void>();
  /** [Basculer sur le chantier N] (F-206 / SF-206-04). */
  readonly switchChantier = output<number>();

  /** Le focus retenu : le parcours en direct, sinon celui de la réponse. */
  private readonly focusNumber = computed(() => this.focus() ?? this.chantier()?.number ?? null);

  readonly shown = computed(() => (this.steps() ?? []).slice(0, MAX_NEXT_STEPS));
  readonly hidden = signal(false);
  /** Les index cochés, dans l'ordre d'affichage. */
  readonly checked = signal<number[]>([]);
  /** Le dernier texte posé dans la saisie par le panneau : tant qu'elle le vaut, il reste ouvert. */
  private readonly written = signal<string | null>(null);
  readonly collapsed = computed(() => {
    const draft = (this.draft() ?? '').trim();
    return this.hidden() || (draft.length > 0 && draft !== (this.written() ?? '').trim());
  });

  /**
   * Une suite « Hors plan » cochée alors que le chantier est ouvert : on le dit, sans rien empêcher
   * (F-196, décision 3).
   */
  readonly remainingWarning = computed(() => {
    const c = this.chantier();
    if (!c) {
      return null;
    }
    const remaining = c.total - c.done;
    const deepening = this.checked().some((i) => this.shown()[i]?.scope === 'APPROFONDISSEMENT');
    return deepening && remaining > 0 ? remainingStepsText(remaining) : null;
  });

  constructor() {
    // Un nouveau tour : panneau rouvert, rien de sélectionné.
    effect(() => {
      this.turnKey();
      this.hidden.set(false);
      this.checked.set([]);
      this.written.set(null);
    });
  }

  label(value: string): string {
    return nextStepLabel(value);
  }

  scopeLabel(step: AtelierNextStep): string | null {
    return nextStepScopeLabel(step, this.chantier() !== null, this.focusNumber());
  }

  otherChantier(step: AtelierNextStep): number | null {
    return nextStepOtherChantier(step, this.chantier() !== null, this.focusNumber());
  }

  progressText(c: AtelierNextStepsChantier): string {
    return chantierProgressText(c, this.focusNumber());
  }

  tone(value: string): string {
    return nextStepTone(value);
  }

  isChecked(index: number): boolean {
    return this.checked().includes(index);
  }

  /** Coche ou décoche une suite, puis pose l'enchaînement des cochées dans la saisie. */
  toggle(index: number): void {
    if (!this.shown()[index]) {
      return;
    }
    const next = this.isChecked(index)
      ? this.checked().filter((i) => i !== index)
      : [...this.checked(), index].sort((a, b) => a - b);
    this.checked.set(next);
    const text = composeNextSteps(next.map((i) => this.shown()[i].request));
    this.written.set(text);
    this.pick.emit(text);
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
    // Entrée après avoir coché : la main à la saisie (pour relire puis envoyer) — et non une coche de plus.
    if (event.key === 'Enter' && this.checked().length > 0) {
      event.preventDefault();
      this.other.emit();
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
      this.toggle(index);
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
