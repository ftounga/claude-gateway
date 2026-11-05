import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { maskSecrets } from '../scenes/jarvis-scene-reader';
import { CardTone, JarvisCard } from './jarvis-card.models';
import { TONE_TOKENS, cardFreshness, safeLink, templateFor, toneOf } from './jarvis-card-templates';

/**
 * F-248 / SF-248-03 — **la pancarte** (cadrage §4.1) : **un seul composant** pour tous les faits ; le type ne change
 * que des **données** (gabarit : forme, mot). Titre, statut (mot écrit + ton de la palette §23, rouge réservé aux
 * alertes), 3-5 champs choisis par la passerelle, lien rapide, badge de fraîcheur (« relevé 18 h 40 », « ancien » ⇒
 * atténuée). Peau **verre** (3D, §23) ou **plate** (repli 2D / téléphone, sans flou). Chaque pancarte est un bouton
 * (« Ouvrir PROJ-123 ») ; les valeurs passent encore par `maskSecrets` (filet, en plus de la liste blanche serveur).
 */
@Component({
  selector: 'app-jarvis-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let c = card();
    <article class="jc" [class.jc--flat]="flat()" [class.jc--hero]="hero()" [class.jc--old]="freshness().old"
             [class.jc--lit]="lit()" [attr.data-tone]="tone()" [attr.data-type]="c.type"
             [style.--jc-tone]="toneToken()">
      <button type="button" class="jc__open" (click)="opened.emit(c)" [attr.aria-label]="'Ouvrir ' + c.title">
        <span class="jc__head">
          <span class="jc__glyph" aria-hidden="true">{{ template().icon }}</span>
          <span class="jc__kind">{{ template().word }}</span>
          @if (freshness().label) {
            <span class="jc__fresh">{{ freshness().label }}</span>
          }
          @if (freshness().old) {
            <span class="jc__old">ancien</span>
          }
        </span>
        <span class="jc__title">{{ c.title }}</span>
        @if (c.status?.word) {
          <span class="jc__status">{{ c.status!.word }}</span>
        }
        @if (fields().length) {
          <span class="jc__fields">
            @for (f of fields(); track f.label) {
              <span class="jc__field"><span class="jc__label">{{ f.label }}</span>
                <span class="jc__value" [class.jc__value--mono]="mono(f.label)">{{ f.value }}</span></span>
            }
          </span>
        }
      </button>
      @if (link(); as l) {
        @if (l.external) {
          <a class="jc__link" [href]="l.href" target="_blank" rel="noopener noreferrer"
             (click)="linked.emit(c)">Ouvrir le lien ↗</a>
        } @else {
          <a class="jc__link" [href]="l.href" (click)="linked.emit(c)">Voir le chantier ›</a>
        }
      }
    </article>
  `,
  styles: [`
    :host { display: block; }
    .jc { --jc-tone: var(--cg-holo, #E07B39); position: relative; box-sizing: border-box; width: 100%;
      display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: 4px; overflow: hidden;
      color: var(--cg-holo-text, #F0954F); font-family: Inter, sans-serif;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 72%, transparent);
      border: 1px solid color-mix(in srgb, var(--jc-tone) 55%, transparent);
      box-shadow: 0 0 12px color-mix(in srgb, var(--jc-tone) 18%, transparent);
      backdrop-filter: blur(10px);
      clip-path: polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px);
      transition: opacity 320ms cubic-bezier(.16,1,.3,1), box-shadow 320ms cubic-bezier(.16,1,.3,1); }
    .jc--flat { backdrop-filter: none; clip-path: none; border-radius: 8px; box-shadow: none;
      background: var(--cg-navy-2, #141A2E); color: var(--cg-cream, #FFF3E4); }
    .jc--hero { border-width: 2px; }
    .jc--lit { box-shadow: 0 0 24px color-mix(in srgb, var(--jc-tone) 45%, transparent); }
    .jc--old { opacity: .45; }
    .jc__open { all: unset; cursor: pointer; display: flex; flex-direction: column; gap: 6px; min-height: 44px; }
    .jc__open:focus-visible { outline: 2px solid var(--cg-holo-core, #FFF3E4); outline-offset: 2px; }
    .jc__head { display: flex; align-items: center; gap: 6px; font: 600 11px 'Space Grotesk', Inter, sans-serif;
      text-transform: uppercase; letter-spacing: .12em; }
    .jc__glyph { color: var(--jc-tone); font-size: 14px; }
    .jc__kind { flex: 1; min-width: 0; }
    .jc__fresh, .jc__old { font: 500 11px 'JetBrains Mono', monospace; letter-spacing: 0; text-transform: none;
      opacity: .85; }
    .jc__old { padding: 0 4px; border: 1px solid currentColor; border-radius: 2px; }
    .jc__title { font: 600 15px/20px 'Space Grotesk', Inter, sans-serif; color: var(--cg-holo-core, #FFF3E4);
      overflow-wrap: anywhere; max-height: 40px; overflow: hidden; }
    .jc--flat .jc__title { color: inherit; }
    .jc__status { align-self: flex-start; padding: 2px 8px; border-radius: 2px; font: 600 12px Inter, sans-serif;
      color: var(--cg-navy, #0B1020); background: var(--jc-tone); }
    .jc[data-tone='eteint'] .jc__status { color: var(--cg-holo-core, #FFF3E4); }
    .jc__fields { display: grid; grid-template-columns: auto 1fr; gap: 2px 8px; font-size: 12px; line-height: 16px; }
    .jc__field { display: contents; }
    .jc__label { opacity: .75; white-space: nowrap; }
    .jc__value { color: var(--cg-holo-core, #FFF3E4); overflow-wrap: anywhere; }
    .jc--flat .jc__value { color: inherit; }
    .jc__value--mono { font-family: 'JetBrains Mono', monospace; }
    .jc__link { align-self: flex-start; min-height: 32px; display: inline-flex; align-items: center;
      font: 600 12px 'Space Grotesk', Inter, sans-serif; color: var(--cg-holo-text, #F0954F); }
    .jc--flat .jc__link { color: var(--cg-orange-2, #F0954F); }
    @media (prefers-reduced-motion: reduce) { .jc { transition: none; } }
  `],
})
export class JarvisCardComponent {
  readonly card = input.required<JarvisCard>();
  /** L'heure de l'écran (ms), pour le badge de fraîcheur. */
  readonly now = input<number>(Date.now());
  /** Le poste du fait est connecté (« à l'instant » possible). */
  readonly connected = input(false);
  /** Peau plate (repli 2D, téléphone, dégradation D13) : sans perspective ni flou. */
  readonly flat = input(false);
  /** Le bloc unique ou la pancarte avancée. */
  readonly hero = input(false);
  /** Citée à l'instant (la phrase qui la nomme est jouée). */
  readonly lit = input(false);
  readonly opened = output<JarvisCard>();
  readonly linked = output<JarvisCard>();

  readonly template = computed(() => templateFor(this.card().type));
  readonly tone = computed<CardTone>(() => toneOf(this.card()));
  readonly toneToken = computed(() => TONE_TOKENS[this.tone()]);
  readonly freshness = computed(() => cardFreshness(this.card().observedAt, this.now(), this.connected()));
  readonly link = computed(() => safeLink(this.card().link));
  /** 5 champs au plus, valeurs masquées par le filet de l'écran. */
  readonly fields = computed(() => (this.card().fields ?? []).slice(0, 5)
    .map((f) => ({ label: f.label, value: maskSecrets(f.value) })));

  /** Clés, commandes, versions : en JetBrains Mono. */
  mono(label: string): boolean {
    return /^(commande|version|image|branche|pipeline|compte)$/i.test(label);
  }
}
