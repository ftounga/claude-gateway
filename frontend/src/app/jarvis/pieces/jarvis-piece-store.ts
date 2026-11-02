import { Injectable, computed, inject, signal } from '@angular/core';

import { JarvisPieceClient, PieceQuery } from './jarvis-piece-client';
import { GalleryMode, ShowCommand, ShowContext, ShowRefusal, showPlan } from './jarvis-piece-show';
import { storyOrder } from './jarvis-piece-story';
import {
  FAST_CLOSE_MS, PieceCountKey, PieceCounter, categoryKey, kindKey, originKey,
} from './jarvis-piece-measure';
import { versionPiece } from './jarvis-piece-versions';
import { NavAction, NavOrigin, NavReason, NavScope, PieceNav, initialNav, reduceNav, selectedItem } from './jarvis-piece-nav';
import { Piece } from './jarvis-piece.models';

/** Refus de la galerie : ceux du réducteur, plus le mode présentation et l'index injoignable. */
export type PieceStoreReason = NavReason | 'PRESENTATION' | 'UNAVAILABLE' | Exclude<ShowRefusal, 'PRESENTATION'>;

export interface PieceStoreResult {
  ok: boolean;
  reason?: PieceStoreReason;
}

/**
 * F-237 / SF-237-05 — **l'état des pièces à l'écran** : l'état de navigation (réducteur pur SF-237-04) et les pièces
 * chargées. Une seule porte d'entrée (`dispatch`) pour le toucher, la voix locale et, plus tard, l'outil `afficher`.
 * **Mode présentation** (D11, Q1) : aucune pièce n'est rendue, toute ouverture est refusée.
 */
@Injectable({ providedIn: 'root' })
export class JarvisPieceStore {
  private readonly client = inject(JarvisPieceClient);
  private loading: AbortController | null = null;

  readonly nav = signal<PieceNav>(initialNav());
  readonly pieces = signal<ReadonlyMap<string, Piece>>(new Map());
  readonly presentation = signal(false);
  /** F-237 / SF-237-12 — l'instant de lecture de la vidéo / de l'audio ouvert (s), pour horodater un retour. */
  readonly mediaTime = signal<number | null>(null);
  /** F-237 / SF-237-08 — la présentation de la galerie : éventail, comparaison, récit, planche contact. */
  readonly mode = signal<GalleryMode>('fan');
  /** F-237 / SF-237-08 — pièces montrées d'initiative, et quand (garde des 10 min, §7). */
  private readonly shownProactively = new Map<string, number>();
  /** Une galerie ou une pièce est ouverte. */
  readonly open = computed(() => {
    const level = this.nav().level;
    return level !== 'UNIVERS' && level !== 'CHANTIER';
  });
  /** F-237 / SF-237-11 — l'avant / après : les deux premières cartes, en mode comparaison. */
  readonly comparePair = computed<[Piece, Piece] | null>(() => {
    if (this.mode() !== 'compare') {
      return null;
    }
    const items = this.nav().items;
    const a = items[0] ? this.pieces().get(items[0].id) : undefined;
    const b = items[1] ? this.pieces().get(items[1].id) : undefined;
    return a && b ? [a, b] : null;
  });
  /** La pièce héros, avec ses métadonnées complètes. */
  readonly selected = computed<Piece | null>(() => {
    const item = selectedItem(this.nav());
    return item ? this.pieces().get(item.id) ?? null : null;
  });

  /** F-237 / SF-237-14 — la mesure (§19) : posée par l'écran, sans contenu. */
  counter: PieceCounter | null = null;
  private galleryOpenedAt = 0;
  private mediaSeconds = 0;
  private lastMediaTime: number | null = null;

  /** Compte une clé du vocabulaire fermé (aucun contenu). */
  count(key: PieceCountKey, n = 1): void {
    if (n > 0) {
      this.counter?.(key, n);
    }
  }

  /**
   * Applique une action ; en présentation, seule la fermeture passe. `source` (SF-237-14) : d'où vient l'ordre —
   * voix locale, toucher, ou IA (cerveau rapide / ligne d'Opus) — compté s'il aboutit.
   */
  dispatch(action: NavAction, source?: 'voice' | 'touch' | 'ai', now = Date.now()): PieceStoreResult {
    if (this.presentation() && action.type !== 'close') {
      return { ok: false, reason: 'PRESENTATION' };
    }
    const before = this.nav();
    const result = reduceNav(before, action);
    if (result.ok) {
      this.nav.set(result.state);
      this.measure(before, result.state, action, source, now);
    }
    return result.ok ? { ok: true } : { ok: false, reason: result.reason };
  }

  /** F-237 / SF-237-14 — ce que l'action change, compté (ordres, pièces ouvertes, pages, fermetures rapides). */
  private measure(before: PieceNav, after: PieceNav, action: NavAction, source: 'voice' | 'touch' | 'ai' | undefined,
    now: number): void {
    if (source) {
      this.count(source === 'voice' ? 'n_loc' : source === 'touch' ? 'n_tch' : 'n_ai');
    }
    if (action.type === 'openGallery') {
      this.galleryOpenedAt = now;
      this.count('p_gal');
      const category = categoryKey(action.scope.category);
      if (category) {
        this.count(category);
      }
      this.count(originKey(after.origin));
      return;
    }
    if (after.selected != null && after.selected !== before.selected) {
      this.count(kindKey(after.items[after.selected - 1]?.kind));
      this.mediaSeconds = 0;
      this.lastMediaTime = null;
    }
    if (action.type === 'page') {
      this.count('pdf_pg');
    }
    const closing = (action.type === 'close' || action.type === 'back' || action.type === 'up')
      && (before.level === 'GALERIE' || before.level === 'PIECE');
    if (closing && before.origin === 'PROACTIVE' && now - this.galleryOpenedAt < FAST_CLOSE_MS) {
      this.count('p_fclose');
    }
  }

  /** F-237 / SF-237-14 — l'instant de lecture avance : les secondes lues sont comptées (sauts exclus). */
  mediaTick(t: number): void {
    this.mediaTime.set(t);
    const last = this.lastMediaTime;
    this.lastMediaTime = t;
    if (last == null || t <= last || t - last > 2) {
      return;
    }
    this.mediaSeconds += t - last;
    if (this.mediaSeconds >= 1) {
      const whole = Math.floor(this.mediaSeconds);
      this.mediaSeconds -= whole;
      this.count('v_sec', whole);
    }
  }

  /** Ouvre une galerie : lit l'index (annule la lecture précédente), puis l'état passe en GALERIE. */
  async openGallery(scope: NavScope, query: PieceQuery, origin: NavOrigin = 'ASKED'): Promise<PieceStoreResult> {
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    this.loading?.abort();
    const controller = new AbortController();
    this.loading = controller;
    const response = await this.client.list(query, controller.signal);
    if (controller.signal.aborted) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    this.loading = null;
    if (!response) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    return this.show(scope, response.pieces, origin);
  }

  /** Montre des pièces déjà en main (pièce produite en direct, tests). */
  show(scope: NavScope, pieces: readonly Piece[], origin: NavOrigin = 'ASKED'): PieceStoreResult {
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    const map = new Map<string, Piece>();
    for (const piece of pieces) {
      map.set(piece.id, piece);
    }
    this.pieces.set(map);
    return this.dispatch({ type: 'openGallery', scope, pieces, origin });
  }

  /**
   * F-237 / SF-237-08 — **un ordre du cerveau rapide** (`afficher`, événement `show`) : la même porte que le toucher
   * et la voix locale. Les garde-fous écran (présentation, hors sujet, 10 min, sobre) sont ceux de `showPlan`.
   */
  async applyShow(cmd: ShowCommand, ctx: Omit<ShowContext, 'nav' | 'recent' | 'presentation' | 'now'>,
    now = Date.now()): Promise<PieceStoreResult> {
    const plan = showPlan(cmd, {
      ...ctx, nav: this.nav(), recent: this.shownProactively, presentation: this.presentation(), now,
    });
    if (plan.kind === 'refuse') {
      return { ok: false, reason: plan.reason };
    }
    if (plan.kind === 'nav') {
      return this.dispatch(plan.action, 'ai');
    }
    this.loading?.abort();
    const controller = new AbortController();
    this.loading = controller;
    const response = await this.client.list(plan.query, controller.signal);
    if (controller.signal.aborted) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    this.loading = null;
    if (!response) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    let pieces = response.pieces;
    if (plan.ids.length) {
      const byId = new Map(pieces.map((p) => [p.id, p]));
      // F-237 / SF-237-11 — une version antérieure (page v1 ↔ v3) : l'index ne tient que la courante.
      const version = (id: string): Piece | null => {
        const base = pieces.find((p) => p.id.split('@')[0] === id.split('@')[0]);
        return base ? versionPiece(base, id) : null;
      };
      pieces = plan.ids.map((id) => byId.get(id) ?? version(id)).filter((p): p is Piece => p != null);
    }
    if (!pieces.length) {
      return { ok: false, reason: 'NOT_FOUND' };
    }
    if (plan.mode === 'story') {
      pieces = storyOrder(pieces); // F-237 / SF-237-10 — besoin → entrées → preuves → productions → envois
    }
    if (plan.origin === 'PROACTIVE') {
      for (const p of pieces) {
        this.shownProactively.set(p.id, now);
      }
    }
    this.mode.set(plan.mode);
    if (plan.mode === 'compare') {
      this.count('p_cmp');
    } else if (plan.mode === 'contact') {
      this.count('p_sheet');
    }
    const shown = this.show(plan.scope, pieces, plan.origin);
    if (shown.ok && plan.select != null) {
      return this.dispatch({ type: 'select', ref: plan.select }, 'ai');
    }
    return shown;
  }

  /**
   * F-237 / SF-237-09 — **une pièce produite en direct** (capture de preuve publiée pendant le tour, T-G7) : elle
   * rejoint la galerie ouverte du même sujet, sinon ouvre la galerie des preuves.
   */
  addLive(scope: NavScope, pieces: readonly Piece[]): PieceStoreResult {
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    if (!pieces.length) {
      return { ok: false, reason: 'NOT_FOUND' };
    }
    const nav = this.nav();
    const open = nav.scope != null && nav.level !== 'UNIVERS' && nav.level !== 'CHANTIER'
      && nav.scope.subjectId === scope.subjectId && (nav.scope.category == null || nav.scope.category === 'PREUVE');
    if (open) {
      const map = new Map(this.pieces());
      for (const piece of pieces) {
        map.set(piece.id, piece);
      }
      this.pieces.set(map);
      return this.dispatch({ type: 'append', pieces });
    }
    this.mode.set('fan');
    return this.show(scope, pieces, 'ASKED');
  }

  /** Tout ferme (sortie de l'écran, bascule en présentation). */
  reset(): void {
    this.loading?.abort();
    this.loading = null;
    this.nav.set(initialNav());
    this.pieces.set(new Map());
    this.mode.set('fan');
  }
}

/** Ce que Jarvis dit d'un refus — court, jamais technique. */
export function refusalPhrase(reason: PieceStoreReason | undefined): string {
  switch (reason) {
    case 'OUT_OF_RANGE':
      return 'Il n’y en a pas d’autre.';
    case 'NOT_APPLICABLE':
      return 'Ce n’est pas possible sur cette pièce.';
    case 'NO_GALLERY':
      return 'Aucune galerie n’est ouverte.';
    case 'NOT_FOUND':
      return 'Je ne la trouve pas.';
    case 'PRESENTATION':
      return 'Pas de pièce en mode présentation.';
    case 'NOT_IN_SCOPE':
      return 'Cette pièce n’est pas du sujet en cours.';
    case 'RECENT':
    case 'NOTHING':
      return '';
    default:
      return 'Je n’arrive pas à lire les pièces pour le moment.';
  }
}
