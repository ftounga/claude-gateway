import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { maskSecrets } from '../scenes/jarvis-scene-reader';
import { JarvisCardDetail } from './jarvis-card-client';
import { CardField, JarvisCard } from './jarvis-card.models';
import { TONE_TOKENS, cardFreshness, safeLink, templateFor, toneOf } from './jarvis-card-templates';

const SOURCES: Record<string, string> = {
  tour: 'vu pendant un tour', releve: 'relevé automatique', relevé: 'relevé automatique', veille: 'veille',
  constat: 'constat', direct: 'lu en direct',
};

/** Une ligne de comparaison : le libellé, les deux valeurs, et si elles diffèrent. */
export interface CompareRow {
  label: string;
  a: string;
  b: string;
  differs: boolean;
}

/** Les champs de deux pancartes alignés par libellé (ordre de la première, puis ceux de la seconde seule). Pur. */
export function compareRows(a: readonly CardField[], b: readonly CardField[]): CompareRow[] {
  const labels = [...new Set([...a.map((f) => f.label), ...b.map((f) => f.label)])];
  return labels.map((label) => {
    const va = a.find((f) => f.label === label)?.value ?? '—';
    const vb = b.find((f) => f.label === label)?.value ?? '—';
    return { label, a: va, b: vb, differs: va !== vb };
  });
}

/**
 * F-248 / SF-248-06 — **le plein écran d'une pancarte** (cadrage §4.5, D6) : tous les champs (lus à l'ouverture), la
 * valeur précédente et son heure, la source, le sujet et le chantier, « Ouvrir le lien », « Ouvrir sur le grand écran »
 * (cockpit externe, s'il est ouvert). Avec `other` : **deux pancartes côte à côte**, champs alignés, différences
 * soulignées et marquées d'un mot (« diffère »), jamais d'une couleur seule. Texte seul ; filet `maskSecrets`.
 */
@Component({
  selector: 'app-jarvis-card-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let c = card();
    <section class="jcd" role="dialog" aria-modal="true" [attr.aria-label]="'Pancarte ' + c.title"
             [style.--jc-tone]="tone(c)">
      <header class="jcd__head">
        <span class="jcd__kind">{{ word(c) }}</span>
        <button type="button" class="jcd__btn" (click)="closed.emit()" aria-label="Fermer la pancarte">✕</button>
      </header>
      @if (other(); as o) {
        <h2 class="jcd__title">{{ c.title }} <span class="jcd__vs">et</span> {{ o.title }}</h2>
        <table class="jcd__compare">
          <thead><tr><th scope="col"></th><th scope="col">{{ c.title }}</th><th scope="col">{{ o.title }}</th></tr></thead>
          <tbody>
            @for (row of rows(); track row.label) {
              <tr [class.jcd__diff]="row.differs">
                <th scope="row">{{ row.label }}@if (row.differs) { <span class="jcd__mark">diffère</span> }</th>
                <td>{{ row.a }}</td><td>{{ row.b }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <h2 class="jcd__title">{{ c.title }}</h2>
        @if (c.status?.word) {
          <p class="jcd__status">{{ c.status!.word }}</p>
        }
        @if (previous(); as p) {
          <p class="jcd__prev">Passé de <em>{{ p.value }}</em> à <em>{{ c.status?.word ?? '—' }}</em>{{ p.when ? ' — ' + p.when : '' }}</p>
        }
        <dl class="jcd__fields">
          @for (f of fields(); track f.label) {
            <dt>{{ f.label }}</dt><dd>{{ f.value }}</dd>
          }
        </dl>
        <p class="jcd__meta">
          @if (freshness()) { <span>{{ freshness() }}</span> }
          @if (source()) { <span>{{ source() }}</span> }
          @if (detail()?.subjectName) {
            <span>Sujet {{ detail()!.subjectName }}{{ detail()!.chantier != null ? ', chantier ' + detail()!.chantier : '' }}</span>
          }
        </p>
        @if (loading()) { <p class="jcd__meta">Lecture du détail…</p> }
      }
      <footer class="jcd__actions">
        @if (link(); as l) {
          <a class="jcd__btn" [href]="l.href" [attr.target]="l.external ? '_blank' : null"
             [attr.rel]="l.external ? 'noopener noreferrer' : null" (click)="linked.emit(c)">Ouvrir le lien</a>
        }
        @if (canBig()) {
          <button type="button" class="jcd__btn" (click)="big.emit(c)">Ouvrir sur le grand écran</button>
        }
      </footer>
    </section>
  `,
  styles: [`
    :host { position: fixed; inset: 0; z-index: 8; display: grid; place-items: center; pointer-events: none; }
    .jcd { --jc-tone: var(--cg-holo, #E07B39); pointer-events: auto; box-sizing: border-box;
      width: min(720px, calc(100vw - 16px)); max-height: calc(100dvh - 32px); overflow: auto; padding: 16px;
      display: flex; flex-direction: column; gap: 8px; border-radius: 8px; color: var(--cg-cream, #FFF3E4);
      background: color-mix(in srgb, var(--cg-navy-2, #141A2E) 94%, transparent);
      border: 1px solid color-mix(in srgb, var(--jc-tone) 55%, transparent); font-family: Inter, sans-serif; }
    .jcd__head { display: flex; align-items: center; justify-content: space-between; gap: 8px;
      font: 600 12px 'Space Grotesk', Inter, sans-serif; text-transform: uppercase; letter-spacing: .18em; }
    .jcd__title { margin: 0; font: 600 20px/26px 'Space Grotesk', Inter, sans-serif; overflow-wrap: anywhere; }
    .jcd__vs { opacity: .7; font-weight: 400; }
    .jcd__status { align-self: flex-start; margin: 0; padding: 2px 8px; border-radius: 2px;
      font: 600 13px Inter, sans-serif; color: var(--cg-navy, #0B1020); background: var(--jc-tone); }
    .jcd__prev { margin: 0; font-size: 14px; }
    .jcd__fields { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; margin: 0; font-size: 14px; }
    .jcd__fields dt { opacity: .75; }
    .jcd__fields dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
    .jcd__meta { display: flex; flex-wrap: wrap; gap: 12px; margin: 0; font: 12px 'JetBrains Mono', monospace;
      opacity: .85; }
    .jcd__compare { width: 100%; border-collapse: collapse; font-size: 13px; }
    .jcd__compare th, .jcd__compare td { text-align: left; padding: 4px 6px; vertical-align: top;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-cream, #FFF3E4) 12%, transparent); overflow-wrap: anywhere; }
    .jcd__diff td { text-decoration: underline; text-underline-offset: 3px; }
    .jcd__mark { margin-left: 6px; font: 600 11px 'Space Grotesk', Inter, sans-serif; text-transform: uppercase; }
    .jcd__actions { display: flex; flex-wrap: wrap; gap: 8px; }
    .jcd__btn { min-height: 44px; min-width: 44px; display: inline-flex; align-items: center; justify-content: center;
      padding: 0 12px; border-radius: 4px; cursor: pointer; color: var(--cg-orange-2, #F0954F);
      background: transparent; border: 1px solid color-mix(in srgb, var(--cg-orange-2, #F0954F) 55%, transparent);
      font: 600 14px 'Space Grotesk', Inter, sans-serif; text-decoration: none; }
  `],
})
export class JarvisCardDetailComponent {
  readonly card = input.required<JarvisCard>();
  readonly detail = input<JarvisCardDetail | null>(null);
  readonly loading = input(false);
  /** Comparaison : la seconde pancarte, et son détail. */
  readonly other = input<JarvisCard | null>(null);
  readonly otherDetail = input<JarvisCardDetail | null>(null);
  /** Un cockpit externe écoute (SF-236-24). */
  readonly canBig = input(false);
  readonly now = input<number>(Date.now());
  readonly closed = output<void>();
  readonly big = output<JarvisCard>();
  readonly linked = output<JarvisCard>();

  /** Tous les champs du détail (sinon ceux de la pancarte), masqués. */
  readonly fields = computed(() => this.masked(this.detail()?.fields ?? this.card().fields ?? []));
  readonly rows = computed(() => compareRows(this.masked(this.detail()?.fields ?? this.card().fields ?? []),
    this.masked(this.otherDetail()?.fields ?? this.other()?.fields ?? [])));
  readonly link = computed(() => safeLink(this.card().link));
  readonly freshness = computed(() => cardFreshness(this.card().observedAt, this.now(), false).label);
  readonly source = computed(() => SOURCES[this.card().source ?? ''] ?? '');
  readonly previous = computed(() => {
    const p = this.detail()?.previous;
    if (!p?.value) {
      return null;
    }
    const when = cardFreshness(p.at ?? null, this.now(), false).label.replace(/^relevé /, '');
    return { value: maskSecrets(p.value), when };
  });

  word(card: JarvisCard): string {
    return templateFor(card.type).word;
  }

  tone(card: JarvisCard): string {
    return TONE_TOKENS[toneOf(card)];
  }

  private masked(fields: readonly CardField[]): CardField[] {
    return fields.map((f) => ({ label: f.label, value: maskSecrets(f.value) }));
  }
}
