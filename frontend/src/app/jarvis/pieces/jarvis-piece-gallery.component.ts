import { ChangeDetectionStrategy, Component, OnDestroy, computed, effect, inject, input, output, signal,
  untracked } from '@angular/core';

import { JarvisState } from '../jarvis-orb.component';
import { JarvisPieceClient } from './jarvis-piece-client';
import { JarvisPieceViewerComponent } from './jarvis-piece-viewer.component';
import { JarvisPieceCompareComponent } from './jarvis-piece-compare.component';
import { arcAroundHero, fanLayout } from './jarvis-piece-fan';
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

/** F-264 (cadrage F-237 §8.4, T-G1) — les cartes jaillissent du centre : échelle de départ, écart entre cartes. */
export const BURST_SCALE = 0.2;
export const BURST_STAGGER_MS = 60;

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
               [class.pg--contact]="store.mode() === 'contact'" [class.pg--low]="lowQuality()"
               [class.pg--calm]="sober()"
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
            @if (store.deckNote(); as note) {
              <!-- F-248 / SF-248-07 — poste éteint : dit une fois, en pied du jeu, jamais par pancarte. -->
              <p class="pg__deck-note">{{ note }}</p>
            }
          }
        } @else if (layout() !== 'phone' || nav().selected == null) {
          <div class="pg__cards" [class.pg__cards--fan]="layout() === 'fan'">
            @if (layout() === 'fan') {
              <!-- F-264 — le socle de lumière sous l'arc : la galerie est un hologramme projeté. -->
              <span class="pg__floor" aria-hidden="true"></span>
            }
            @for (item of nav().items; track item.id; let i = $index) {
              <button type="button" class="pg__card" [attr.data-category]="category(item)"
                      [class.pg__card--hero]="nav().selected === item.index"
                      [style.transform]="layout() === 'fan' ? transform(item) : null"
                      [style.opacity]="layout() === 'fan' ? alpha(item) : null"
                      [style.--pg-fx]="layout() === 'fan' ? burstFrom(item) : null"
                      [style.animation-delay.ms]="i * burstStagger"
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
          <article class="pg__panel" [class.pg__panel--arch]="piece.kind === 'ARCHITECTURE' && arch3d()"
                   (pointerdown)="swipeStart($event)" (pointerup)="swipeEnd($event)">
            <h2 class="pg__panel-title">{{ nav().selected }}. {{ piece.title }}</h2>
            <!-- SF-237-06 — le contenu réel, par la visionneuse de son type ; F-238 / SF-238-10 : l'architecture en 3D. -->
            <app-jarvis-piece-viewer [piece]="piece" [nav]="nav()" [jarvisState]="jarvisState()" [allow3d]="arch3d()"
                                     [spoken]="spoken()" [phone]="phone()" [low]="lowQuality()" />
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
    /* F-264 — un calque plein écran AU-DESSUS de tout (cockpit z 3, étiquettes z 2), sous le plein écran d'une
       pancarte (z 8) : la galerie n'est plus prisonnière de la colonne ni collée en bas. */
    :host { position: fixed; inset: 0; pointer-events: none; z-index: 7; --pg-spread: 1; --pg-card-w: 220px;
      --pg-card-h: 216px; --pg-thumb-h: 124px; }
    @media (min-width: 1700px) { :host { --pg-spread: 1.25; --pg-card-w: 260px; --pg-card-h: 244px; --pg-thumb-h: 150px; } }
    @media (max-width: 1199px) { :host { --pg-spread: .8; --pg-card-w: 190px; --pg-card-h: 200px; --pg-thumb-h: 108px; } }
    .pg { position: absolute; inset: 0; display: flex; flex-direction: column; pointer-events: auto;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 82%, transparent);
      color: var(--cg-holo-text, #F0954F); font-family: Inter, sans-serif; }
    /* Éventail : un voile radial assombrit le fond (transcriptions, cockpit) ; le toucher le traverse. */
    .pg--fan { pointer-events: none;
      background: radial-gradient(ellipse 70% 62% at 50% 52%, color-mix(in srgb, var(--cg-navy, #0B1020) 80%, transparent) 0%,
        color-mix(in srgb, var(--cg-navy, #0B1020) 62%, transparent) 62%, color-mix(in srgb, var(--cg-navy, #0B1020) 40%, transparent) 100%); }
    .pg--fan .pg__bar, .pg--fan .pg__card, .pg--fan .pg__panel, .pg--fan .pg__more { pointer-events: auto; }
    .pg__bar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px 16px;
      flex: none; }
    .pg__crumbs { font-family: 'Space Grotesk', Inter, sans-serif; font-weight: 600; text-transform: uppercase;
      letter-spacing: .12em; font-size: 12px; text-shadow: 0 0 12px color-mix(in srgb, var(--cg-holo, #E07B39) 60%, transparent); }
    .pg__tools { display: flex; gap: 8px; }
    .pg__btn { min-width: 44px; min-height: 44px; border-radius: 4px; cursor: pointer;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 75%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent);
      box-shadow: 0 0 12px color-mix(in srgb, var(--cg-holo, #E07B39) 30%, transparent);
      color: var(--cg-holo-text, #F0954F); font: 600 14px 'Space Grotesk', Inter, sans-serif; padding: 0 12px; }

    /* La carte : verre holographique (§23) — reflet du ton, liseré lumineux, halo. Aucune couleur nouvelle. */
    .pg__card { position: relative; display: flex; flex-direction: column; gap: 4px; align-items: stretch;
      text-align: left; cursor: pointer; padding: 12px; border-radius: 4px; color: inherit; font: inherit;
      overflow: hidden; box-sizing: border-box;
      background: linear-gradient(180deg, color-mix(in srgb, var(--pg-tint) 22%, transparent), transparent 55%),
        color-mix(in srgb, var(--cg-navy, #0B1020) 80%, transparent);
      border: 1px solid color-mix(in srgb, var(--pg-tint) 70%, transparent);
      box-shadow: 0 0 24px color-mix(in srgb, var(--pg-tint) 45%, transparent),
        inset 0 0 16px color-mix(in srgb, var(--pg-tint) 16%, transparent);
      --pg-tint: var(--cg-holo-core, #FFF3E4);
      animation: pg-rise 420ms var(--jv-ease-sortie, cubic-bezier(.16,1,.3,1)) backwards; }
    .pg__card[data-category='PREUVE'] { --pg-tint: var(--cg-holo-green, #4CF2A0); }
    .pg__card[data-category='PRODUCTION'] { --pg-tint: var(--cg-holo, #E07B39); }
    .pg__card--hero { border-width: 2px; box-shadow: 0 0 40px color-mix(in srgb, var(--pg-tint) 60%, transparent),
      inset 0 0 24px color-mix(in srgb, var(--pg-tint) 24%, transparent); }
    .pg__index { font: 600 18px 'JetBrains Mono', monospace; color: var(--pg-tint);
      text-shadow: 0 0 10px color-mix(in srgb, var(--pg-tint) 70%, transparent); }
    .pg__thumb { width: 100%; height: var(--pg-thumb-h); object-fit: cover; border-radius: 4px;
      box-shadow: 0 0 0 1px color-mix(in srgb, var(--pg-tint) 40%, transparent); }
    .pg__glyph { height: var(--pg-thumb-h); display: grid; place-items: center; font-size: 40px; color: var(--pg-tint);
      text-shadow: 0 0 18px color-mix(in srgb, var(--pg-tint) 70%, transparent); }
    .pg__title { font: 600 14px/18px Inter, sans-serif; max-height: 36px; overflow: hidden;
      color: var(--cg-holo-core, #FFF3E4); }
    .pg__meta { font: 12px 'JetBrains Mono', monospace; opacity: .8; }

    /* Éventail (3D) : l'arc au milieu de l'écran ; les cartes jaillissent du centre (T-G1). */
    .pg__cards.pg__cards--fan { position: absolute; left: 0; right: 0; top: 52%; height: 0; perspective: 1400px;
      display: block; width: auto; margin: 0; padding: 0; max-height: none;
      transform-style: preserve-3d; overflow: visible; transform-origin: 50% 0;
      transition: top 600ms var(--jv-ease-glisse, cubic-bezier(.65,0,.35,1)),
        scale 600ms var(--jv-ease-glisse, cubic-bezier(.65,0,.35,1)); }
    .pg__cards--fan .pg__card { position: absolute; left: calc(50% - var(--pg-card-w) / 2);
      top: calc(var(--pg-card-h) / -2); width: var(--pg-card-w); height: var(--pg-card-h);
      animation: pg-jaillit 700ms var(--jv-ease-rebond, cubic-bezier(.34,1.3,.64,1)) backwards;
      transition: transform 500ms var(--jv-ease-glisse, cubic-bezier(.65,0,.35,1)),
        opacity 500ms var(--jv-ease-glisse, cubic-bezier(.65,0,.35,1)); }
    /* L'éclat qui balaie le verre (coupé en qualité basse, sobre, mouvement réduit). */
    .pg__cards--fan .pg__card::after { content: ''; position: absolute; inset: 0; pointer-events: none;
      background: linear-gradient(105deg, transparent 38%, color-mix(in srgb, var(--cg-holo-core, #FFF3E4) 20%, transparent) 50%, transparent 62%);
      translate: -110% 0; animation: pg-eclat 4.5s var(--jv-ease-glisse, ease-in-out) 1s infinite; }
    .pg__floor { position: absolute; left: 50%; top: calc(var(--pg-card-h) / 2 - 24px);
      width: calc(1180px * var(--pg-spread)); height: 120px; translate: -50% 0; pointer-events: none;
      background: radial-gradient(ellipse 50% 50% at 50% 50%, color-mix(in srgb, var(--cg-holo, #E07B39) 38%, transparent),
        color-mix(in srgb, var(--cg-holo, #E07B39) 10%, transparent) 55%, transparent 72%);
      animation: pg-socle 3.2s var(--jv-ease-souffle, ease-in-out) infinite alternate; }
    .pg__more { position: absolute; left: 50%; top: calc(var(--pg-card-h) / 2 + 24px); translate: -50% 0;
      font: 600 16px 'JetBrains Mono', monospace; color: var(--cg-holo-core, #FFF3E4); }
    /* Une pièce choisie : l'arc descend sous elle, réduit ; le panneau prend le centre (T-G3). */
    .pg--fan.pg--hero .pg__cards--fan { top: calc(100% - 118px); scale: .62; }
    /* … et le bas de l'écran s'assombrit : les transcriptions ne transparaissent plus sous l'arc. */
    .pg--fan.pg--hero { background:
      linear-gradient(0deg, var(--cg-navy, #0B1020) 0%, color-mix(in srgb, var(--cg-navy, #0B1020) 94%, transparent) 22%,
        transparent 42%),
      radial-gradient(ellipse 70% 62% at 50% 45%, color-mix(in srgb, var(--cg-navy, #0B1020) 80%, transparent) 0%,
        color-mix(in srgb, var(--cg-navy, #0B1020) 62%, transparent) 62%, color-mix(in srgb, var(--cg-navy, #0B1020) 40%, transparent) 100%); }
    .pg--fan .pg__panel { position: absolute; left: 50%; top: calc(56px + (100% - 56px - 236px) / 2);
      translate: -50% -50%; margin: 0; width: min(980px, calc(100% - 64px)); max-height: calc(100% - 56px - 248px);
      animation: pg-heros 600ms var(--jv-ease-sortie, cubic-bezier(.16,1,.3,1)) backwards; }
    /* F-238 / SF-238-10 — l'architecture en 3D : grande, au centre ; la visionneuse remplit le panneau. */
    .pg .pg__panel.pg__panel--arch { display: flex; flex-direction: column; overflow: hidden;
      width: min(1600px, calc(100% - 64px)); height: calc(100% - 56px - 200px); max-height: none; }
    .pg--fan.pg--hero .pg__panel.pg__panel--arch { top: calc(56px + (100% - 56px - 188px) / 2); }
    .pg__panel--arch app-jarvis-piece-viewer { position: relative; flex: 1; min-height: 360px; }
    .pg.pg--phone .pg__panel.pg__panel--arch { width: 100%; height: auto; flex: 1; }
    .pg__panel { margin: 0 auto 16px; width: min(960px, calc(100% - 32px)); max-height: 60vh; overflow: auto;
      padding: 16px; border-radius: 4px; box-sizing: border-box;
      background: linear-gradient(180deg, color-mix(in srgb, var(--cg-holo, #E07B39) 13%, transparent), transparent 40%),
        color-mix(in srgb, var(--cg-navy, #0B1020) 88%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 70%, transparent);
      box-shadow: 0 0 48px color-mix(in srgb, var(--cg-holo, #E07B39) 35%, transparent),
        inset 0 0 24px color-mix(in srgb, var(--cg-holo, #E07B39) 12%, transparent); }
    .pg__panel-title { margin: 0 0 8px; font: 600 18px 'Space Grotesk', Inter, sans-serif;
      color: var(--cg-holo-core, #FFF3E4); }
    .pg__caption { margin: 8px 0 0; font-size: 14px; line-height: 20px; color: var(--cg-holo-core, #FFF3E4); }
    .pg__hint { margin: 8px 0 0; font-size: 12px; opacity: .8; }

    /* Grille (repli 2D, sobre, mouvement réduit) : au centre de tout l'écran. */
    /* Fond quasi opaque : les transcriptions et réglages de l'écran ne transparaissent plus entre les cartes. */
    .pg--grid { background: color-mix(in srgb, var(--cg-navy, #0B1020) 94%, transparent); }
    .pg__cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px;
      width: min(1100px, calc(100% - 32px)); margin: auto; padding: 16px; box-sizing: border-box;
      overflow-y: auto; max-height: calc(100% - 80px); }
    /* Une pièce choisie en 2D : elle prend le centre, les autres en bande dessous (comme l'arc en 3D). */
    .pg--grid.pg--hero .pg__cards { display: flex; flex: none; order: 2; margin: 0 auto; max-height: none;
      overflow-x: auto; overflow-y: hidden; padding: 8px 16px 16px; }
    .pg--grid.pg--hero .pg__panel { order: 1; }
    .pg__cards { scrollbar-width: thin;
      scrollbar-color: color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent) transparent; }
    .pg--grid.pg--hero .pg__card { flex: 0 0 160px; --pg-thumb-h: 72px; }
    .pg--grid .pg__panel { margin: auto; width: min(1000px, calc(100% - 32px)); max-height: calc(100% - 240px); }
    .pg--phone .pg__cards { grid-template-columns: repeat(2, 1fr); width: 100%; margin: 0; gap: 12px; }
    .pg--phone .pg__panel { margin: 0 auto 16px; }
    .pg--contact .pg__cards { grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); }
    .pg--contact .pg__thumb, .pg--contact .pg__glyph { height: 120px; }
    .pg--phone.pg--hero .pg__panel { flex: 1; max-height: none; margin: 0; width: 100%; border-radius: 0; }

    @keyframes pg-jaillit { from { opacity: 0; translate: var(--pg-fx, 0) 0; scale: .2; } 55% { opacity: 1; }
      to { translate: 0 0; scale: 1; } }
    @keyframes pg-rise { from { opacity: 0; translate: 0 24px; } }
    @keyframes pg-heros { from { opacity: 0; scale: .85; } }
    @keyframes pg-eclat { 0% { translate: -110% 0; } 40%, 100% { translate: 110% 0; } }
    @keyframes pg-socle { from { opacity: .7; } to { opacity: 1; } }
    /* Qualité basse : mêmes lueurs (ombres fixes), plus aucune animation continue. Sobre : rien ne bouge. */
    .pg--low .pg__card::after, .pg--calm .pg__card::after { display: none; }
    .pg--low .pg__floor, .pg--calm .pg__floor { animation: none; }
    .pg--calm .pg__card, .pg--calm .pg__panel { animation: none; }
    @media (prefers-reduced-motion: reduce) {
      .pg__card, .pg__panel, .pg__floor, .pg__card::after { animation: none !important; }
      .pg__cards--fan .pg__card { transition: opacity 400ms linear; }
    }
    /* F-248 / SF-248-04 — le jeu de pancartes (DESIGN_SYSTEM §25). */
    .pg__deck { position: relative; margin: auto 0; padding: 0 16px 16px; pointer-events: auto; } /* F-264 : au milieu */
    .pg__deck--center, .pg__deck--fan { height: 300px; perspective: 1200px; transform-style: preserve-3d; }
    .pg__deck--center .pg__slot, .pg__deck--fan .pg__slot { position: absolute; left: calc(50% - 120px); bottom: 24px;
      width: 240px; transition: transform 500ms cubic-bezier(.65,0,.35,1); }
    .pg__deck--center .pg__slot { left: calc(50% - 150px); width: 300px; bottom: 120px; }
    .pg__deck--wall { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px;
      width: min(960px, 100%); margin: auto; box-sizing: border-box; }
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
    .pg__deck-note { margin: 0 auto 12px; pointer-events: auto; font: 500 12px 'JetBrains Mono', monospace;
      color: var(--cg-holo-text, #F0954F); opacity: .85; text-align: center; }
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
  /** F-238 / SF-238-10 — la phrase de Jarvis en train d'être jouée (narration d'une architecture ouverte). */
  readonly spoken = input<string>('');
  /** F-248 / SF-248-04 — la pastille « N pancartes » touchée. */
  readonly cardsWanted = output<void>();
  /** F-248 / SF-248-06 — une pancarte à ouvrir en plein écran (détail). */
  readonly cardOpened = output<JarvisCard>();
  /** F-248 / SF-248-01 — un lien rapide ouvert (compté). */
  readonly cardLinked = output<JarvisCard>();
  readonly maxAnimated = MAX_ANIMATED;
  /** F-264 (T-G1) — l'écart entre deux cartes qui jaillissent. */
  readonly burstStagger = BURST_STAGGER_MS;
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

  readonly phone = signal(typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches);
  private readonly reducedMotion = signal(typeof matchMedia === 'function'
    && matchMedia('(prefers-reduced-motion: reduce)').matches);

  readonly nav = this.store.nav;
  /** F-238 / SF-238-10 — l'architecture s'ouvre en 3D : rendu 3D actif, ni sobre ni mouvement réduit. */
  readonly arch3d = computed(() => this.threeD() && !this.sober() && !this.reducedMotion());
  readonly layout = computed(() => galleryLayout({ threeD: this.threeD(), sober: this.sober(),
    reducedMotion: this.reducedMotion(), phone: this.phone(), contact: this.store.mode() === 'contact' }));
  private readonly slots = computed(() => arcAroundHero(fanLayout(this.nav().items.length, this.nav().selected)));
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
    // F-264 — l'écart de l'arc suit la largeur de l'écran (`--pg-spread`, posé par la feuille de style).
    return slot ? `translate3d(calc(${slot.x}px * var(--pg-spread, 1)), 0, ${slot.z}px) rotateY(${-slot.angle}deg) `
      + `scale(${slot.scale})` : '';
  }

  /**
   * F-264 (T-G1) — d'où la carte jaillit : du centre de l'arc. L'échelle de départ ({@link BURST_SCALE}) réduit aussi
   * son décalage dans l'arc ; la translation compense le reste pour que toutes partent du même point.
   */
  burstFrom(item: NavItem): string {
    const x = this.slots()[item.index - 1]?.x ?? 0;
    return `calc(${-x * BURST_SCALE}px * var(--pg-spread, 1))`;
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
