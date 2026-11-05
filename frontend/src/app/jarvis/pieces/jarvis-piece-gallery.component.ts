import { ChangeDetectionStrategy, Component, OnDestroy, computed, effect, inject, input, output, signal,
  untracked } from '@angular/core';

import { JarvisState } from '../jarvis-orb.component';
import { JarvisPieceClient } from './jarvis-piece-client';
import { JarvisPieceViewerComponent } from './jarvis-piece-viewer.component';
import { JarvisPieceCompareComponent } from './jarvis-piece-compare.component';
import { fanLayout } from './jarvis-piece-fan';
import { NavAction, NavItem } from './jarvis-piece-nav';
import { JarvisPieceStore, PieceStoreResult } from './jarvis-piece-store';
import { PieceCategory, PieceKind } from './jarvis-piece.models';
import { JarvisCardComponent } from '../cards/jarvis-card.component';
import { JarvisCard } from '../cards/jarvis-card.models';
import { ENTRY_STAGGER_MS, MAX_ANIMATED, deckLayout, deckSlots, flatDeck, groupLabel, wallPage } from '../cards/jarvis-card-deck';

/** Disposition de la galerie : éventail (3D), grille (repli 2D), téléphone (une pièce plein écran). */
export type GalleryLayout = 'fan' | 'grid' | 'phone';

/** Choix de la disposition (pur, exporté pour les tests). */
export function galleryLayout(opts: { threeD: boolean; sober: boolean; reducedMotion: boolean; phone: boolean;
  contact?: boolean }): GalleryLayout {
  if (opts.phone) {
    return 'phone';
  }
  if (opts.contact) {
    return 'grid'; // F-237 / SF-237-11 — la planche contact : tout d'un coup d'œil, en grille
  }
  return opts.threeD && !opts.sober && !opts.reducedMotion ? 'fan' : 'grid';
}

const KIND_ICONS: Record<PieceKind, string> = {
  IMAGE: '▣', VIDEO: '▶', AUDIO: '♪', PDF: '▤', PAGE: '❏', SLIDES: '◫', ARCHITECTURE: '⌬', DIAGRAM: '⌗',
  TEXT: '¶', FILE: '◇', FAIT: '◈',
};

const KIND_WORDS: Record<PieceKind, string> = {
  IMAGE: 'Image', VIDEO: 'Vidéo', AUDIO: 'Audio', PDF: 'PDF', PAGE: 'Page', SLIDES: 'Diaporama',
  ARCHITECTURE: 'Architecture', DIAGRAM: 'Schéma', TEXT: 'Texte', FILE: 'Fichier', FAIT: 'Pancarte',
};

const CATEGORY_WORDS: Record<PieceCategory, string> = { ENTREE: 'Entrée', PREUVE: 'Preuve', PRODUCTION: 'Production' };

const WHEN = new Intl.DateTimeFormat('fr-FR', {
  weekday: 'long', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Paris',
});

/** Seuil d'un balayage (px). */
const SWIPE_PX = 60;

/**
 * F-237 / SF-237-05 — **la galerie des pièces** (cadrage §8.1, §8.6) : éventail holographique sur PC (arc de 110°,
 * perspective CSS au-dessus de la scène), grille en repli 2D (WebGL absent, sobre, mouvement réduit), et au
 * téléphone une pièce à la fois en plein écran (balayage gauche / droite = suivante / précédente, vers le bas =
 * retour). Le toucher envoie les **mêmes actions** que la voix au même réducteur. Rien n'est rendu en présentation.
 */
@Component({
  selector: 'app-jarvis-piece-gallery',
  standalone: true,
  imports: [JarvisPieceViewerComponent, JarvisPieceCompareComponent, JarvisCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.open() && !store.presentation()) {
      <section class="pg" [class.pg--fan]="layout() === 'fan'" [class.pg--grid]="layout() === 'grid'"
               [class.pg--phone]="layout() === 'phone'" [class.pg--hero]="nav().selected != null"
               [class.pg--contact]="store.mode() === 'contact'"
               role="region" aria-label="Pièces montrées par Jarvis">
        <header class="pg__bar">
          <nav class="pg__crumbs" aria-label="Fil d'Ariane">{{ crumbs() }}</nav>
          <div class="pg__tools">
            @if (store.pendingCards().length && store.mode() !== 'cards') {
              <!-- F-248 / SF-248-04 — une galerie demandée n'est jamais remplacée : les pancartes attendent ici. -->
              <button type="button" class="pg__btn pg__pending" (click)="cardsWanted.emit()">
                {{ store.pendingCards().length }} pancarte{{ store.pendingCards().length > 1 ? 's' : '' }}
              </button>
            }
            @if (nav().selected != null) {
              <button type="button" class="pg__btn" (click)="act({ type: 'prev' })" aria-label="Pièce précédente">‹</button>
              <button type="button" class="pg__btn" (click)="act({ type: 'next' })" aria-label="Pièce suivante">›</button>
              <button type="button" class="pg__btn" (click)="act({ type: 'back' })">Galerie</button>
              @if (canPin()) {
                <button type="button" class="pg__btn" (click)="pin.emit()" aria-label="Épingler au cockpit">Cockpit</button>
              }
            }
            <button type="button" class="pg__btn" (click)="act({ type: 'close' })" aria-label="Fermer les pièces">✕</button>
          </div>
        </header>

        @if (store.mode() === 'cards') {
          @if (nav().selected == null || !flat()) {
            <!-- F-248 / SF-248-04 (D5) — le jeu : 1 au centre, 2-6 en éventail, ≥ 7 en mur paginé « et N autres ». -->
            <div class="pg__deck" [class.pg__deck--flat]="flat()" [class.pg__deck--center]="!flat() && deckKind() === 'center'"
                 [class.pg__deck--fan]="!flat() && deckKind() === 'fan'" [class.pg__deck--wall]="!flat() && deckKind() === 'wall'"
                 (pointerdown)="swipeStart($event, true)" (pointerup)="deckSwipeEnd($event)">
              @for (c of deckCards(); track c.id; let i = $index) {
                @if (groupHead(i); as g) {
                  <h3 class="pg__group">{{ g }}</h3>
                }
                <div class="pg__slot" [class.pg__slot--in]="i < maxAnimated" [style.animation-delay.ms]="i * stagger"
                     [style.transform]="slotTransform(i)">
                  <app-jarvis-card [card]="c" [now]="now()" [flat]="flat()" [connected]="c.source === 'direct'"
                                   [hero]="deckKind() === 'center' || nav().selected === indexOf(c)"
                                   [lit]="litIds().includes(c.id)"
                                   (opened)="openCard(c)" (linked)="cardLinked.emit(c)" />
                </div>
              }
              @if (wall().more > 0) {
                <button type="button" class="pg__more-cell" (click)="nextPage()">et {{ wall().more }} autres</button>
              }
            </div>
          }
        } @else if (layout() !== 'phone' || nav().selected == null) {
          <div class="pg__cards" [class.pg__cards--fan]="layout() === 'fan'">
            @for (item of nav().items; track item.id) {
              <button type="button" class="pg__card" [attr.data-category]="category(item)"
                      [class.pg__card--hero]="nav().selected === item.index"
                      [style.transform]="layout() === 'fan' ? transform(item) : null"
                      [style.opacity]="layout() === 'fan' ? alpha(item) : null"
                      [style.display]="layout() === 'fan' && !visible(item) ? 'none' : null"
                      (click)="act({ type: 'select', ref: item.index })"
                      [attr.aria-label]="item.index + '. ' + item.title">
                <span class="pg__index">{{ item.index }}</span>
                @if (thumbs()[item.id]; as src) {
                  <img class="pg__thumb" [src]="src" alt="" loading="lazy">
                } @else {
                  <span class="pg__glyph" aria-hidden="true">{{ icon(item.kind) }}</span>
                }
                <span class="pg__title">{{ item.title }}</span>
                <span class="pg__meta">{{ categoryWord(item) }} · {{ kindWord(item.kind) }}</span>
                @if (store.mode() === 'contact' && item.at) {
                  <span class="pg__meta">{{ when(item.at) }}</span>
                }
              </button>
            }
            @if (nav().more > 0) {
              <span class="pg__more">+{{ nav().more }}</span>
            }
          </div>
        }

        @if (store.comparePair(); as pair) {
          <article class="pg__panel">
            <h2 class="pg__panel-title">Avant / après</h2>
            <!-- SF-237-11 — rideau glissant (deux images) ou deux visionneuses côte à côte. -->
            <app-jarvis-piece-compare [before]="pair[0]" [after]="pair[1]" [nav]="nav()" [jarvisState]="jarvisState()" />
          </article>
        } @else if (selectedFact()) {
          @let fact = selectedFact()!;
          <!-- F-248 / SF-248-04 — la pancarte choisie, en grand (le plein écran détaillé vient en SF-248-06). -->
          <article class="pg__panel pg__panel--card" (pointerdown)="swipeStart($event)" (pointerup)="swipeEnd($event)">
            <app-jarvis-card [card]="fact" [now]="now()" [flat]="flat()" [hero]="true"
                             [connected]="fact.source === 'direct'" (opened)="cardOpened.emit(fact)"
                             (linked)="cardLinked.emit(fact)" />
          </article>
        } @else if (store.selected()) {
          @let piece = store.selected()!;
          <article class="pg__panel" (pointerdown)="swipeStart($event)" (pointerup)="swipeEnd($event)">
            <h2 class="pg__panel-title">{{ nav().selected }}. {{ piece.title }}</h2>
            <!-- SF-237-06 — le contenu réel, par la visionneuse de son type. -->
            <app-jarvis-piece-viewer [piece]="piece" [nav]="nav()" [jarvisState]="jarvisState()" />
            @if (piece.caption && piece.kind !== 'TEXT') {
              <p class="pg__caption">{{ piece.caption }}</p>
            }
            @if (piece.chantierLink === 'WINDOW' && piece.chantier != null) {
              <p class="pg__hint">Rattachée au chantier {{ piece.chantier }} d’après la date.</p>
            }
          </article>
        }
      </section>
    }
  `,
  styles: [`
    :host { position: absolute; inset: 0; pointer-events: none; z-index: 6; }
    .pg { position: absolute; inset: 0; display: flex; flex-direction: column; pointer-events: auto;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 72%, transparent);
      color: var(--cg-holo-text, #F0954F); font-family: Inter, sans-serif; }
    .pg--fan { background: transparent; pointer-events: none; }
    .pg--fan .pg__bar, .pg--fan .pg__cards, .pg--fan .pg__panel { pointer-events: auto; }
    .pg__bar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px 16px; }
    .pg__crumbs { font-family: 'Space Grotesk', Inter, sans-serif; font-weight: 600; text-transform: uppercase;
      letter-spacing: .12em; font-size: 12px; }
    .pg__tools { display: flex; gap: 8px; }
    .pg__btn { min-width: 44px; min-height: 44px; border-radius: 4px; cursor: pointer;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 75%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent);
      color: var(--cg-holo-text, #F0954F); font: 600 14px 'Space Grotesk', Inter, sans-serif; padding: 0 12px; }
    .pg__cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px;
      padding: 0 16px 16px; overflow-y: auto; }
    .pg__cards--fan { position: relative; display: block; height: 240px; margin-top: auto; perspective: 1200px;
      transform-style: preserve-3d; overflow: visible; }
    .pg__cards--fan .pg__card { position: absolute; left: calc(50% - 80px); bottom: 24px; width: 160px;
      transition: transform 500ms cubic-bezier(.65,0,.35,1), opacity 500ms cubic-bezier(.65,0,.35,1); }
    .pg__card { display: flex; flex-direction: column; gap: 4px; align-items: stretch; text-align: left;
      cursor: pointer; padding: 8px; border-radius: 4px; color: inherit; font: inherit;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 75%, transparent);
      border: 1px solid color-mix(in srgb, var(--pg-tint) 55%, transparent);
      box-shadow: 0 0 12px color-mix(in srgb, var(--pg-tint) 18%, transparent);
      --pg-tint: var(--cg-holo-core, #FFF3E4); }
    .pg__card[data-category='PREUVE'] { --pg-tint: var(--cg-holo-green, #4CF2A0); }
    .pg__card[data-category='PRODUCTION'] { --pg-tint: var(--cg-holo, #E07B39); }
    .pg__card--hero { border-width: 2px; }
    .pg__index { font: 600 16px 'JetBrains Mono', monospace; color: var(--pg-tint); }
    .pg__thumb { width: 100%; height: 88px; object-fit: cover; border-radius: 4px; }
    .pg__glyph { height: 88px; display: grid; place-items: center; font-size: 32px; color: var(--pg-tint); }
    .pg__title { font-size: 13px; line-height: 16px; max-height: 32px; overflow: hidden; }
    .pg__meta { font: 12px 'JetBrains Mono', monospace; opacity: .75; }
    .pg__more { position: absolute; right: 16px; bottom: 8px; font: 600 14px 'JetBrains Mono', monospace; }
    .pg__panel { margin: 0 auto 16px; width: min(960px, calc(100% - 32px)); max-height: 60vh; overflow: auto;
      padding: 16px; border-radius: 4px; background: color-mix(in srgb, var(--cg-navy, #0B1020) 75%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent); }
    .pg--fan .pg__panel { position: absolute; left: 50%; top: 64px; transform: translateX(-50%); }
    .pg__panel-title { margin: 0 0 8px; font: 600 16px 'Space Grotesk', Inter, sans-serif; }
    .pg__caption { margin: 8px 0 0; font-size: 14px; line-height: 20px; color: var(--cg-holo-core, #FFF3E4); }
    .pg__hint { margin: 8px 0 0; font-size: 12px; opacity: .8; }
    .pg--phone .pg__cards { grid-template-columns: repeat(2, 1fr); }
    .pg--contact .pg__cards { grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); }
    .pg--contact .pg__thumb, .pg--contact .pg__glyph { height: 120px; }
    .pg--phone.pg--hero .pg__panel { flex: 1; max-height: none; margin: 0; width: 100%; border-radius: 0; }
    @media (prefers-reduced-motion: reduce) { .pg__cards--fan .pg__card { transition: opacity 400ms linear; } }
    /* F-248 / SF-248-04 — le jeu de pancartes (DESIGN_SYSTEM §25). */
    .pg__deck { position: relative; margin-top: auto; padding: 0 16px 16px; pointer-events: auto; }
    .pg__deck--center, .pg__deck--fan { height: 300px; perspective: 1200px; transform-style: preserve-3d; }
    .pg__deck--center .pg__slot, .pg__deck--fan .pg__slot { position: absolute; left: calc(50% - 120px); bottom: 24px;
      width: 240px; transition: transform 500ms cubic-bezier(.65,0,.35,1); }
    .pg__deck--center .pg__slot { left: calc(50% - 150px); width: 300px; bottom: 120px; }
    .pg__deck--wall { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px;
      width: min(960px, 100%); margin: auto auto 16px; box-sizing: border-box; }
    .pg__deck--wall .pg__group { grid-column: 1 / -1; }
    .pg__deck--flat { display: flex; flex-direction: column; gap: 8px; overflow-y: auto; max-height: calc(100dvh - 140px);
      padding: 0 8px 16px; margin-top: 0; }
    .pg__group { margin: 4px 0 0; font: 600 12px 'Space Grotesk', Inter, sans-serif; text-transform: uppercase;
      letter-spacing: .18em; color: var(--cg-holo-text, #F0954F); }
    .pg__slot--in { animation: pg-card-in 320ms cubic-bezier(.16,1,.3,1) both; }
    @keyframes pg-card-in { from { opacity: 0; translate: 0 40px; scale: .6; } to { opacity: 1; translate: 0 0; scale: 1; } }
    .pg__more-cell { min-height: 44px; cursor: pointer; border-radius: 4px; color: var(--cg-holo-text, #F0954F);
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 75%, transparent);
      border: 1px dashed color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent);
      font: 600 14px 'Space Grotesk', Inter, sans-serif; }
    .pg__panel--card { max-width: 560px; }
    .pg--fan .pg__deck { pointer-events: auto; }
    @media (prefers-reduced-motion: reduce) { .pg__slot--in { animation: none; } .pg__slot { transition: none; } }
  `],
})
export class JarvisPieceGalleryComponent implements OnDestroy {
  readonly store = inject(JarvisPieceStore);
  private readonly client = inject(JarvisPieceClient);

  /** La scène 3D est-elle rendue ? */
  readonly threeD = input(false);
  /** Mode sobre (F-234 D9) : vignettes seules, pas d'éventail. */
  readonly sober = input(false);
  /** Libellé du sujet pour le fil d'Ariane. */
  readonly subjectLabel = input<string | null>(null);
  /** F-237 / SF-237-12 — un cockpit est ouvert : la pièce peut y être épinglée. */
  readonly canPin = input(false);
  /** F-237 / SF-237-12 — « Épingler au cockpit ». */
  readonly pin = output<void>();
  /** L'état de Jarvis, pour le son des pièces (SF-237-06, D10). */
  readonly jarvisState = input<JarvisState>('repos');
  /** F-248 / SF-248-04 (D13) — qualité Basse : les pancartes passent à plat. */
  readonly lowQuality = input(false);
  /** F-248 / SF-248-05 — les pancartes citées à l'instant (la phrase qui les nomme est jouée). */
  readonly litIds = input<readonly string[]>([]);
  /** F-248 / SF-248-04 — la pastille « N pancartes » touchée. */
  readonly cardsWanted = output<void>();
  /** F-248 / SF-248-06 — une pancarte à ouvrir en plein écran (détail). */
  readonly cardOpened = output<JarvisCard>();
  /** F-248 / SF-248-01 — un lien rapide ouvert (compté). */
  readonly cardLinked = output<JarvisCard>();
  readonly maxAnimated = MAX_ANIMATED;
  readonly stagger = ENTRY_STAGGER_MS;
  /** L'heure de l'écran, pour les badges de fraîcheur (relue à chaque jeu). */
  readonly now = computed(() => {
    this.store.deck();
    return Date.now();
  });
  readonly flat = computed(() => flatDeck({ threeD: this.threeD(), sober: this.sober(),
    reducedMotion: this.reducedMotion(), phone: this.phone(), lowQuality: this.lowQuality() }));
  /** La pancarte choisie (type FAIT), s'il y en a une. */
  readonly selectedFact = computed<JarvisCard | null>(() => {
    const piece = this.store.selected();
    return piece?.kind === 'FAIT' ? this.store.cards().get(piece.id) ?? null : null;
  });
  readonly deckKind = computed(() => deckLayout(this.store.deck().length));
  readonly wall = computed(() => wallPage(this.store.deck(), this.store.deckPage()));
  /** Les pancartes dessinées : tout le jeu (centre, éventail, pile du téléphone) ou la page du mur. */
  readonly deckCards = computed<JarvisCard[]>(() => {
    const deck = this.store.deck();
    return this.flat() ? deck : this.deckKind() === 'wall' ? this.wall().cards : deck;
  });
  private readonly deckSlotList = computed(() => this.deckKind() === 'wall' || this.flat() ? []
    : deckSlots(this.deckCards().length));

  private readonly phone = signal(typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches);
  private readonly reducedMotion = signal(typeof matchMedia === 'function'
    && matchMedia('(prefers-reduced-motion: reduce)').matches);

  readonly nav = this.store.nav;
  readonly layout = computed(() => galleryLayout({ threeD: this.threeD(), sober: this.sober(),
    reducedMotion: this.reducedMotion(), phone: this.phone(), contact: this.store.mode() === 'contact' }));
  private readonly slots = computed(() => fanLayout(this.nav().items.length, this.nav().selected));
  readonly thumbs = signal<Record<string, string>>({});
  private loading: AbortController | null = null;
  private swipe: { x: number; y: number } | null = null;

  readonly crumbs = computed(() => {
    const nav = this.nav();
    const parts: string[] = [];
    if (this.subjectLabel()) {
      parts.push(this.subjectLabel()!);
    }
    if (nav.scope?.label) {
      parts.push(nav.scope.label);
    }
    if (nav.selected != null) {
      parts.push(`${nav.selected}/${nav.items.length + nav.more}`);
    }
    return parts.join(' › ');
  });

  /** Les vignettes de la galerie affichée : lues à l'ouverture, révoquées au changement. */
  private readonly itemKey = computed(() => this.nav().items.map((item) => item.id).join('|'));
  private readonly loadThumbs = effect(() => {
    this.itemKey(); // seule la liste des cartes recharge les vignettes, pas une sélection
    const pieces = this.store.pieces();
    const items = untracked(() => this.nav().items);
    this.loading?.abort();
    untracked(() => this.revoke()); // lire les vignettes ici ferait boucler l'effet
    const controller = new AbortController();
    this.loading = controller;
    for (const item of items) {
      const path = pieces.get(item.id)?.thumb;
      if (!path) {
        continue;
      }
      void this.client.objectUrl(path, controller.signal).then((url) => {
        if (!url) {
          return;
        }
        if (controller.signal.aborted) {
          URL.revokeObjectURL(url);
          return;
        }
        this.thumbs.update((all) => ({ ...all, [item.id]: url }));
      });
    }
  });

  act(action: NavAction): PieceStoreResult {
    return this.store.dispatch(action, 'touch');
  }

  /** F-248 / SF-248-04 — la position 3D d'une pancarte (centre / éventail) ; rien au mur ni à plat. */
  slotTransform(i: number): string | null {
    const slot = this.deckSlotList()[i];
    if (!slot) {
      return null;
    }
    return `translate3d(${slot.x}px, ${slot.y}px, ${slot.z}px) rotateY(${-slot.angle}deg) scale(${slot.scale})`;
  }

  /** L'en-tête de groupe avant la pancarte `i` (mur et pile seulement), ou `null`. */
  groupHead(i: number): string | null {
    if (this.deckKind() !== 'wall' && !this.flat()) {
      return null;
    }
    const cards = this.deckCards();
    const label = groupLabel(this.store.deck(), cards[i]);
    if (!label) {
      return null;
    }
    return i === 0 || groupLabel(this.store.deck(), cards[i - 1]) !== label ? label : null;
  }

  /** Le rang (1..n) d'une pancarte dans le jeu (celui que dit le PO : « la deuxième »). */
  indexOf(card: JarvisCard): number {
    return this.nav().items.findIndex((i) => i.id === card.id) + 1;
  }

  /** Toucher une pancarte : elle passe au premier plan (et s'ouvre en plein écran, SF-248-06). */
  openCard(card: JarvisCard): void {
    const index = this.indexOf(card);
    if (index > 0) {
      this.act({ type: 'select', ref: index });
    }
    this.cardOpened.emit(card);
  }

  /** « et N autres » : la page suivante du mur. */
  nextPage(): void {
    this.store.nextDeckPage();
  }

  /** Téléphone : un balayage vers le bas sur la pile ferme le jeu. */
  deckSwipeEnd(event: PointerEvent): void {
    if (!this.swipe) {
      return;
    }
    const dy = event.clientY - this.swipe.y;
    const dx = event.clientX - this.swipe.x;
    this.swipe = null;
    if (dy >= 80 && dy > Math.abs(dx) * 1.3) {
      this.act({ type: 'close' });
      this.store.closeCards();
    }
  }

  transform(item: NavItem): string {
    const slot = this.slots()[item.index - 1];
    return slot ? `translate3d(${slot.x}px, 0, ${slot.z}px) rotateY(${-slot.angle}deg) scale(${slot.scale})` : '';
  }

  alpha(item: NavItem): number {
    return this.slots()[item.index - 1]?.alpha ?? 1;
  }

  visible(item: NavItem): boolean {
    return this.slots()[item.index - 1]?.visible ?? true;
  }

  category(item: NavItem): PieceCategory | null {
    return this.store.pieces().get(item.id)?.category ?? null;
  }

  categoryWord(item: NavItem): string {
    const category = this.category(item);
    return category ? CATEGORY_WORDS[category] : '';
  }

  /** « mardi 3 nov., 10:42 » (heure de Paris). */
  when(at: string): string {
    const date = new Date(at);
    return Number.isNaN(date.getTime()) ? '' : WHEN.format(date);
  }

  icon(kind: PieceKind): string {
    return KIND_ICONS[kind] ?? '◇';
  }

  kindWord(kind: PieceKind): string {
    return KIND_WORDS[kind] ?? 'Fichier';
  }

  swipeStart(event: PointerEvent, deck = false): void {
    this.swipe = this.layout() === 'phone' || (deck && this.flat()) ? { x: event.clientX, y: event.clientY } : null;
  }

  /** Téléphone : gauche / droite = suivante / précédente, vers le bas = retour à la galerie. */
  swipeEnd(event: PointerEvent): void {
    if (!this.swipe) {
      return;
    }
    const dx = event.clientX - this.swipe.x;
    const dy = event.clientY - this.swipe.y;
    this.swipe = null;
    const action = swipeAction(dx, dy);
    if (action) {
      this.act(action);
    }
  }

  ngOnDestroy(): void {
    this.loading?.abort();
    this.revoke();
  }

  private revoke(): void {
    for (const url of Object.values(this.thumbs())) {
      URL.revokeObjectURL(url);
    }
    this.thumbs.set({});
  }
}

/** Un balayage du téléphone en action (pur, exporté pour les tests). */
export function swipeAction(dx: number, dy: number): NavAction | null {
  if (Math.abs(dx) >= SWIPE_PX && Math.abs(dx) > Math.abs(dy)) {
    return { type: dx < 0 ? 'next' : 'prev' };
  }
  if (dy >= SWIPE_PX * 1.3 && dy > Math.abs(dx)) {
    return { type: 'back' };
  }
  return null;
}
