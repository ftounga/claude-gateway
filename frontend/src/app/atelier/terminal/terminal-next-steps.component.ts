import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, HostListener, computed, effect, inject, input, output, signal } from '@angular/core';

import {
  AtelierNextStep, AtelierNextStepLabel, AtelierNextStepPrerequisite, AtelierNextStepsChantier,
} from '../../core/models/atelier.models';

/** Options affichées au plus sans chantier ouvert — et touches 1 à 4. */
export const MAX_NEXT_STEPS = 4;

/** Options affichées au plus en deux colonnes (F-209) : 4 « Chantier » + 3 « Hors chantier » — touches 1 à 7. */
export const MAX_NEXT_STEPS_COLUMNS = 7;

/** Les prérequis d'une suite, groupés par niveau (F-209) : un groupe = en parallèle ; les groupes, dans l'ordre. */
export function prerequisiteGroups(step: AtelierNextStep): AtelierNextStepPrerequisite[][] {
  const byLevel = new Map<number, AtelierNextStepPrerequisite[]>();
  for (const p of step.prerequisites ?? []) {
    byLevel.set(p.level, [...(byLevel.get(p.level) ?? []), p]);
  }
  return [...byLevel.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]);
}

/**
 * **« Lancer »** (F-209 / SF-209-02) : UNE demande visible — les prérequis cochés, les parallèles ensemble
 * et les séquentiels dans l'ordre, puis l'option. Sans prérequis coché : la demande de l'option, telle quelle.
 * Sur une ligne (la saisie est mono-ligne).
 */
export function composeLaunch(step: AtelierNextStep, checkedSteps: number[]): string {
  const keep = new Set(checkedSteps);
  const groups = prerequisiteGroups(step)
    .map((g) => g.filter((p) => keep.has(p.step)))
    .filter((g) => g.length > 0);
  if (groups.length === 0) {
    return step.request;
  }
  const parts = groups.map((g, k) => `${k + 1}) ${g.length > 1 ? 'en parallèle : ' : ''}`
    + g.map((p) => `étape ${p.step} « ${p.title} »`).join(', '));
  const chantier = step.chantier ? `Chantier ${step.chantier} — ` : '';
  return `${chantier}d’abord les prérequis, dans cet ordre — ${parts.join(' ; ')}. Puis : ${step.request.trim()}`;
}

/** « Ch.4 · étape 4/14 » (F-209), ou `null` pour une suite sans chantier. */
export function nextStepBadge(step: AtelierNextStep, chantier: AtelierNextStepsChantier | null): string | null {
  if (!chantier || step.scope !== 'CHANTIER' || !step.chantier) {
    return null;
  }
  const progress = [chantier, ...(chantier.others ?? [])].find((c) => c.number === step.chantier);
  if (!step.stepIndex) {
    return `Ch.${step.chantier}`;
  }
  return progress && progress.total > 0
    ? `Ch.${step.chantier} · étape ${step.stepIndex}/${progress.total}`
    : `Ch.${step.chantier} · étape ${step.stepIndex}`;
}

/** « bloquée : après l’étape 3 · attente cert-gino » (F-209), ou `null`. */
export function nextStepBlockedText(step: AtelierNextStep): string | null {
  const blocked = step.blockedBy ?? [];
  if (blocked.length === 0) {
    return null;
  }
  const steps = blocked.filter((b) => b.startsWith('étape ')).map((b) => b.slice('étape '.length));
  const waits = blocked.filter((b) => !b.startsWith('étape '));
  const parts: string[] = [];
  if (steps.length) {
    parts.push(`après ${steps.length > 1 ? 'les étapes' : 'l’étape'} ${steps.join(', ')}`);
  }
  parts.push(...waits);
  return `bloquée : ${parts.join(' · ')}`;
}

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
  imports: [NgTemplateOutlet],
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
          @if (columns()) {
            <!-- F-209 / SF-209-02 : chantier ouvert — à gauche ce qui fait avancer le chantier, à droite le reste. -->
            <div class="cap__cols">
              <div class="cap__col cap__col--chantier" role="group" [attr.aria-label]="chantierHeading()">
                <div class="col__head">
                  <span class="col__title">{{ chantierHeading() }}</span>
                  @if (chantier(); as c) {
                    @if (c.total > 0) {
                      <span class="col__bar" aria-hidden="true"><span class="col__fill" [style.width.%]="percent(c)"></span></span>
                      <span class="col__count">{{ c.done }}/{{ c.total }} faites</span>
                    }
                  }
                </div>
                @for (item of left(); track item.index) {
                  <ng-container [ngTemplateOutlet]="option" [ngTemplateOutletContext]="{ $implicit: item.step, i: item.index }" />
                }
              </div>
              @if (right().length > 0) {
                <div class="cap__col cap__col--hors" role="group" aria-label="Hors chantier">
                  <div class="col__head"><span class="col__title">Hors chantier</span></div>
                  @for (item of right(); track item.index) {
                    <ng-container [ngTemplateOutlet]="option" [ngTemplateOutletContext]="{ $implicit: item.step, i: item.index }" />
                  }
                </div>
              }
            </div>
            <div class="cap__opts">
              <button type="button" class="opt opt--other" (click)="other.emit()">
                <span class="key" aria-hidden="true">…</span>
                <span class="body"><span class="why">Autre chose : tapez votre suite</span></span>
              </button>
            </div>
          } @else {
            <div class="cap__opts">
              @for (step of shown(); track $index; let i = $index) {
                <ng-container [ngTemplateOutlet]="option" [ngTemplateOutletContext]="{ $implicit: step, i: i }" />
              }
              <button type="button" class="opt opt--other" (click)="other.emit()">
                <span class="key" aria-hidden="true">…</span>
                <span class="body"><span class="why">Autre chose : tapez votre suite</span></span>
              </button>
            </div>
          }
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
    <ng-template #option let-step let-i="i">
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
          @if (badge(step); as b) {
            <span class="badge">{{ b }}</span>
          }
          @if (scopeLabel(step); as scope) {
            <span class="scope" [attr.data-scope]="step.scope">{{ scope }}</span>
          }
          @if (blockedText(step); as blocked) {
            <span class="blocked">{{ blocked }}</span>
          }
          <span class="why">{{ step.why }}</span>
        </span>
        <span class="tag" [attr.data-tone]="tone(step.label)">{{ label(step.label) }}</span>
      </button>
      @if (otherChantier(step); as n) {
        <button type="button" class="switch" (click)="switchChantier.emit(n)">Basculer sur le chantier {{ n }}</button>
      }
      @if (step.prerequisites?.length) {
        <div class="pre" role="group" [attr.aria-label]="'Prérequis de l’option ' + (i + 1)">
          <span class="pre__head">{{ prerequisiteHeading(step) }}</span>
          <ol class="pre__groups">
            @for (group of groups(step); track $index; let g = $index) {
              <li class="pre__group">
                @if (groups(step).length > 1) {
                  <span class="pre__lvl">{{ g + 1 }}</span>
                }
                @for (p of group; track p.step) {
                  <label class="pre__item" [class.pre__item--ext]="p.risk === 'EXTERNE'">
                    <input type="checkbox" [checked]="isPrereqChecked(i, step, p)" (change)="togglePrereq(i, step, p)" />
                    <span>étape {{ p.step }} · {{ p.title }}</span>
                    @if (p.risk === 'EXTERNE') {
                      <span class="pre__ext">externe</span>
                    }
                  </label>
                }
              </li>
            }
          </ol>
          <button type="button" class="pre__launch" (click)="askLaunch(i, step)">Lancer</button>
        </div>
      }
    </ng-template>
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
    .cap__title { margin: 0; font: 600 14px 'Inter', sans-serif; color: inherit; }
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
    .cap__cols { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
    .cap__cols:has(> .cap__col:only-child) { grid-template-columns: minmax(0, 1fr); }
    .cap__col { display: grid; align-content: start; min-width: 0; }
    .cap__col--hors { border-left: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); }
    .col__head {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
    }
    .col__title { font: 600 12px 'Inter', sans-serif; color: var(--cg-divider); min-width: 0; overflow: hidden; text-overflow: ellipsis; }
    .col__bar {
      flex: 0 0 64px; height: 4px; border-radius: 2px; overflow: hidden;
      background: color-mix(in srgb, var(--cg-divider) 20%, transparent);
    }
    .col__fill { display: block; height: 100%; background: var(--cg-accent); }
    .col__count { font: 500 12px 'Inter', sans-serif; color: #9FB0C4; }
    .badge {
      justify-self: start; font: 500 11px 'JetBrains Mono', monospace; padding: 0 8px; border-radius: 4px;
      color: var(--cg-accent); border: 1px solid color-mix(in srgb, var(--cg-accent) 35%, transparent);
    }
    .blocked { font-size: 12px; color: #F9A825; }
    .pre {
      display: grid; gap: 4px; margin: -4px 12px 8px 52px; padding: 8px;
      border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); border-radius: 8px; font-size: 12px;
    }
    .pre__head { color: #9FB0C4; }
    .pre__groups { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
    .pre__group { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; }
    .pre__lvl {
      width: 18px; height: 18px; border-radius: 50%; display: grid; place-items: center;
      font: 600 11px 'JetBrains Mono', monospace; color: var(--cg-navy-2); background: var(--cg-divider);
    }
    .pre__item { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
    .pre__item input { accent-color: var(--cg-accent); }
    .pre__ext { color: #F9A825; font: 500 11px 'JetBrains Mono', monospace; }
    .pre__launch, .pre__yes, .pre__no {
      justify-self: start; padding: 4px 12px; border-radius: 4px; cursor: pointer; font: 600 12px 'Inter', sans-serif;
      background: var(--cg-accent); color: var(--cg-navy-2); border: 1px solid var(--cg-accent);
    }
    .pre__no { background: none; color: var(--cg-divider); border-color: color-mix(in srgb, var(--cg-divider) 30%, transparent); }
    .pre__launch:focus-visible, .pre__yes:focus-visible, .pre__no:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
    .cap__foot { display: flex; gap: 16px; flex-wrap: wrap; padding: 8px 12px; font-size: 12px; color: #9FB0C4; }
    kbd {
      font: 11px 'JetBrains Mono', monospace; padding: 0 4px; border-radius: 4px;
      border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); color: var(--cg-divider);
    }
    @media (max-width: 819px) {
      /* F-209 : sur téléphone, les colonnes s'empilent — Chantier en premier. */
      .cap__cols { grid-template-columns: minmax(0, 1fr); }
      .cap__col--hors { border-left: 0; border-top: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); }
      .pre { margin-left: 12px; }
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
  /**
   * Les touches 1-N répondent sans focus dans le panneau (F-210 / SF-210-04) : faux pour les panneaux
   * des cartes suivantes d'une réponse découpée — sinon une touche cocherait dans toutes les cartes.
   */
  readonly globalKeys = input<boolean>(true);

  /**
   * **Volet du split** (F-221 / SF-221-05) : seules les suites du chantier du volet s'affichent — la colonne
   * « Hors chantier » disparaît, y compris pour les tours relus d'avant le split.
   */
  readonly chantierOnly = input<boolean>(false);

  /** Les options cochées : leur enchaînement, à poser dans la saisie — sans envoi (vide ⇒ tout décoché). */
  readonly pick = output<string>();
  /** « Autre chose », ou Entrée après avoir coché : donner la main à la saisie. */
  readonly other = output<void>();
  /** [Basculer sur le chantier N] (F-206 / SF-206-04). */
  readonly switchChantier = output<number>();

  /** Le focus retenu : le parcours en direct, sinon celui de la réponse. */
  private readonly focusNumber = computed(() => this.focus() ?? this.chantier()?.number ?? null);

  /** Deux colonnes (F-209) : la gateway a rangé au moins une suite dans une colonne. */
  readonly columns = computed(() => (this.steps() ?? []).some((s) => !!s.column));

  readonly shown = computed(() => (this.steps() ?? [])
    .slice(0, this.columns() ? MAX_NEXT_STEPS_COLUMNS : MAX_NEXT_STEPS));

  /** Colonne « Chantier » (F-209) : les suites rangées à gauche, avec leur numéro de touche. */
  readonly left = computed(() => this.shown().map((step, index) => ({ step, index }))
    .filter((x) => x.step.column === 'CHANTIER'));

  /** Colonne « Hors chantier » (F-209) : tout le reste. */
  readonly right = computed(() => this.chantierOnly() ? []
    : this.shown().map((step, index) => ({ step, index })).filter((x) => x.step.column !== 'CHANTIER'));

  /** « Chantier 4 — Migration » (F-209). */
  readonly chantierHeading = computed(() => {
    const c = this.chantier();
    const n = this.focusNumber() ?? c?.number ?? null;
    if (!n) {
      return 'Chantier';
    }
    const title = c && c.number === n ? c.title : null;
    return title ? `Chantier ${n} — ${title}` : `Chantier ${n}`;
  });

  /** Les prérequis décochés ou cochés à la main : `option:étape` → coché (F-209). */
  private readonly prereqChoice = signal<Record<string, boolean>>({});

  /** « Lancer » (F-209) : une demande visible, envoyée dans le fil (précision pendant un tour). */
  readonly launch = output<string>();
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
      this.prereqChoice.set({});
    });
  }

  badge(step: AtelierNextStep): string | null {
    return this.columns() ? nextStepBadge(step, this.chantier()) : null;
  }

  blockedText(step: AtelierNextStep): string | null {
    return nextStepBlockedText(step);
  }

  percent(c: AtelierNextStepsChantier): number {
    return c.total > 0 ? Math.round((100 * c.done) / c.total) : 0;
  }

  groups(step: AtelierNextStep): AtelierNextStepPrerequisite[][] {
    return prerequisiteGroups(step);
  }

  prerequisiteHeading(step: AtelierNextStep): string {
    return this.groups(step).length > 1 ? 'Avant, dans l’ordre :' : 'Avant, en parallèle :';
  }

  /** Coché par défaut, sauf une étape EXTERNE (jamais pré-cochée). */
  isPrereqChecked(option: number, step: AtelierNextStep, p: AtelierNextStepPrerequisite): boolean {
    const explicit = this.prereqChoice()[`${option}:${p.step}`];
    return explicit ?? p.risk !== 'EXTERNE';
  }

  togglePrereq(option: number, step: AtelierNextStep, p: AtelierNextStepPrerequisite): void {
    const next = !this.isPrereqChecked(option, step, p);
    this.prereqChoice.update((m) => ({ ...m, [`${option}:${p.step}`]: next }));
  }

  private checkedPrereqs(option: number, step: AtelierNextStep): AtelierNextStepPrerequisite[] {
    return (step.prerequisites ?? []).filter((p) => this.isPrereqChecked(option, step, p));
  }

  /**
   * « Lancer » : la demande part tout de suite, étape externe cochée comprise — aucune confirmation de
   * plus (F-218 / SF-218-03, mode offensif). Cocher l'étape externe EST le choix de l'utilisateur.
   */
  askLaunch(option: number, step: AtelierNextStep): void {
    this.launch.emit(composeLaunch(step, this.checkedPrereqs(option, step).map((p) => p.step)));
  }

  label(value: string): string {
    return nextStepLabel(value);
  }

  scopeLabel(step: AtelierNextStep): string | null {
    // F-209 : en colonnes, le badge « Ch.4 · étape 4/14 » dit déjà le rattachement — reste le titre de l'étape.
    if (this.columns() && step.scope === 'CHANTIER' && !this.otherChantier(step)) {
      return step.stepTitle ?? null;
    }
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
    const element = this.host.nativeElement as HTMLElement;
    if (!this.globalKeys()) {
      return !!active && element.contains(active);
    }
    if (!active || active === document.body) {
      return true;
    }
    const terminal = element.closest('app-atelier-terminal') ?? element;
    return terminal.contains(active);
  }
}
