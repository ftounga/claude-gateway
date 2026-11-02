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
  TEXT: '¶', FILE: '◇',
};

const KIND_WORDS: Record<PieceKind, string> = {
  IMAGE: 'Image', VIDEO: 'Vidéo', AUDIO: 'Audio', PDF: 'PDF', PAGE: 'Page', SLIDES: 'Diaporama',
  ARCHITECTURE: 'Architecture', DIAGRAM: 'Schéma', TEXT: 'Texte', FILE: 'Fichier',
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
  imports: [JarvisPieceViewerComponent, JarvisPieceCompareComponent],
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

        @if (layout() !== 'phone' || nav().selected == null) {
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
    return this.store.dispatch(action);
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

  swipeStart(event: PointerEvent): void {
    this.swipe = this.layout() === 'phone' ? { x: event.clientX, y: event.clientY } : null;
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
