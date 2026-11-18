import { Injectable, computed, inject, signal } from '@angular/core';

import { JarvisPieceClient, PieceQuery } from './jarvis-piece-client';
import { GalleryMode, ShowCommand, ShowContext, ShowRefusal, showPlan } from './jarvis-piece-show';
import { storyOrder } from './jarvis-piece-story';
import {
  FAST_CLOSE_MS, PieceCountKey, PieceCounter, categoryKey, kindKey, originKey,
} from './jarvis-piece-measure';
import { versionPiece } from './jarvis-piece-versions';
import { NavAction, NavOrigin, NavReason, NavScope, PieceNav, initialNav, reduceNav, selectedItem } from './jarvis-piece-nav';
import { ArchNav, ArchOp, ArchRequest, trivialArchOp } from './jarvis-piece-arch';
import { Piece } from './jarvis-piece.models';
import { JarvisCard } from '../cards/jarvis-card.models';
import { LINGER_MS, cardPiece, cardsScope, orderCards, sizeKey, wallPage } from '../cards/jarvis-card-deck';
import { cardFreshness } from '../cards/jarvis-card-templates';

/** Refus de la galerie : ceux du réducteur, plus le mode présentation et l'index injoignable. */
export type PieceStoreReason = NavReason | 'PRESENTATION' | 'UNAVAILABLE' | Exclude<ShowRefusal, 'PRESENTATION'>
  /** F-248 / SF-248-04 — une galerie demandée est ouverte : les pancartes s'annoncent par une pastille. */
  | 'BUSY';

export interface PieceStoreResult {
  ok: boolean;
  reason?: PieceStoreReason;
  /** F-238 / SF-238-10 — la phrase d'un refus précis (« Il y en a deux : RDS primaire et RDS secours. »). */
  detail?: string;
}

/** F-238 / SF-238-10 — une demande d'architecture résolue contre le graphe, ou refusée (et pourquoi). */
export type ArchResolution = { op: ArchOp } | { refusal: 'NOT_FOUND' | 'AMBIGUOUS'; detail?: string };

/** F-238 / SF-238-10 — la visionneuse 3D d'une pièce `ARCHITECTURE` ouverte : elle sait lire son graphe. */
export interface ArchBinding {
  pieceId: string;
  resolve(request: ArchRequest, arch: ArchNav): ArchResolution;
}

/** Une demande d'architecture arrivée avant le graphe (premier cadrage du cerveau rapide) attend au plus 10 s. */
export const ARCH_PENDING_MS = 10_000;

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

  /** F-248 / SF-248-04 — les pancartes du jeu affiché (mode `cards`), par identifiant. */
  readonly cards = signal<ReadonlyMap<string, JarvisCard>>(new Map());
  /** F-248 / SF-248-07 — la mention du pied du jeu (« Poste … éteint depuis … »), une fois. */
  readonly deckNote = signal<string | null>(null);
  /** F-248 / SF-248-04 — la page du mur (≥ 7 pancartes). */
  readonly deckPage = signal(0);
  /** F-248 / SF-248-04 — pancartes en attente derrière une galerie demandée (la pastille « 3 pancartes »). */
  readonly pendingCards = signal<readonly JarvisCard[]>([]);
  /** F-248 / SF-248-04 — les pancartes du jeu, dans l'ordre affiché. */
  readonly deck = computed<JarvisCard[]>(() => {
    if (this.mode() !== 'cards') {
      return [];
    }
    const cards = this.cards();
    return this.nav().items.map((i) => cards.get(i.id)).filter((c): c is JarvisCard => c != null);
  });
  private deckId: string | null = null;
  private deckShownAt = 0;
  private lingerTimer: ReturnType<typeof setTimeout> | null = null;

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
    if (action.type === 'arch' && !action.op) {
      // F-238 / SF-238-10 — la cible (« la base », « le VPC ») se résout contre le graphe de la pièce ouverte.
      const resolved = this.resolveArch(action, source, now);
      if (resolved) {
        return resolved;
      }
    }
    const before = this.nav();
    const result = reduceNav(before, action);
    if (result.ok) {
      this.nav.set(result.state);
      this.measure(before, result.state, action, source, now);
      if (action.type === 'close' && this.mode() === 'cards') {
        this.closeCards(); // F-248 / SF-248-04 — « ferme ça » efface le jeu de pancartes
      }
    }
    return result.ok ? { ok: true } : { ok: false, reason: result.reason };
  }

  /** F-238 / SF-238-11 — une scène d'architecture est posée au centre (l'univers recouvert se suspend). */
  readonly archScene = signal(false);

  /** F-238 / SF-238-10 — la visionneuse 3D liée à la pièce ouverte, et la demande arrivée avant elle. */
  private archBinding: ArchBinding | null = null;
  private pendingArch: { action: Extract<NavAction, { type: 'arch' }>; source?: 'voice' | 'touch' | 'ai'; at: number }
    | null = null;

  /**
   * F-238 / SF-238-10 — la visionneuse 3D se lie à sa pièce : les demandes d'architecture se résolvent contre son
   * graphe ; une demande arrivée juste avant (≤ 10 s, même pièce) s'applique maintenant. Rend la fonction qui délie.
   */
  bindArchitecture(binding: ArchBinding, now = Date.now()): () => void {
    this.archBinding = binding;
    const pending = this.pendingArch;
    this.pendingArch = null;
    if (pending && now - pending.at <= ARCH_PENDING_MS && selectedItem(this.nav())?.id === binding.pieceId) {
      this.dispatch(pending.action, pending.source, now);
    }
    return () => {
      if (this.archBinding === binding) {
        this.archBinding = null;
      }
    };
  }

  /** `null` : l'action continue vers le réducteur telle quelle (demande triviale) ; sinon le résultat. */
  private resolveArch(action: Extract<NavAction, { type: 'arch' }>, source: 'voice' | 'touch' | 'ai' | undefined,
    now: number): PieceStoreResult | null {
    const nav = this.nav();
    const item = selectedItem(nav);
    if (!item || item.kind !== 'ARCHITECTURE' || !nav.arch) {
      if (this.loading) {
        // Le schéma est en train de s'ouvrir (le cerveau envoie « pièce » puis « flux » d'un seul souffle).
        this.pendingArch = { action, source, at: now };
        return { ok: true };
      }
      return { ok: false, reason: 'NOT_APPLICABLE' };
    }
    const binding = this.archBinding;
    if (!binding || binding.pieceId !== item.id) {
      if (trivialArchOp(action.request)) {
        return null;
      }
      this.pendingArch = { action, source, at: now }; // le graphe arrive : la demande attend la visionneuse
      if (action.request.kind === 'focus' && action.request.target.trim()) {
        // En 2D (pas de graphe), le SVG cadré sait déjà cadrer une partie nommée : la même cible, tout de suite.
        return this.dispatch({ type: 'zoom', target: action.request.target.trim() }, source, now);
      }
      return { ok: true };
    }
    const resolution = binding.resolve(action.request, nav.arch);
    if ('refusal' in resolution) {
      if (resolution.refusal === 'AMBIGUOUS') {
        this.count('a_amb'); // F-238 / SF-238-14 — la question « lequel ? » posée
      }
      return { ok: false, reason: resolution.refusal, detail: resolution.detail };
    }
    return this.dispatch({ ...action, op: resolution.op }, source, now);
  }

  /** F-237 / SF-237-14 — ce que l'action change, compté (ordres, pièces ouvertes, pages, fermetures rapides). */
  private measure(before: PieceNav, after: PieceNav, action: NavAction, source: 'voice' | 'touch' | 'ai' | undefined,
    now: number): void {
    if (source) {
      this.count(source === 'voice' ? 'n_loc' : source === 'touch' ? 'n_tch' : 'n_ai');
      if (this.mode() === 'cards' && source !== 'ai') {
        this.count(source === 'voice' ? 'c_voice' : 'c_touch'); // F-248 / SF-248-01 — ordres sur les pancartes
      }
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
    if (action.type === 'arch' && action.op && after.arch !== before.arch) {
      const key = archKey(action.op);
      if (key) {
        this.count(key); // F-238 / SF-238-14 — éclatements, replis, focus, flux, vues, « en 2D »
      }
    }
    const closing = (action.type === 'close' || action.type === 'back' || action.type === 'up')
      && (before.level === 'GALERIE' || before.level === 'PIECE');
    if (closing && before.origin === 'PROACTIVE' && now - this.galleryOpenedAt < FAST_CLOSE_MS) {
      this.count('p_fclose');
    }
    if (action.type === 'close' && this.mode() === 'cards' && now - this.deckShownAt < FAST_CLOSE_MS) {
      this.count('c_close2s'); // F-248 / SF-248-01 — jeu fermé en moins de 2 s (« mal montré »)
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
    if (!response) {
      this.loading = null;
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    let pieces = response.pieces;
    if (plan.ids.length && plan.query.subjectId && !plan.ids.every((id) => hasPiece(pieces, id))) {
      // F-272 / SF-272-04 — une pièce nommée tout juste produite (schéma d'Opus) : relue sans le cache de la passerelle
      // (l'écran reste « en chargement » : un ordre d'architecture arrivé entre-temps attend la visionneuse).
      const fresh = await this.client.list({ ...plan.query, fresh: true }, controller.signal);
      if (controller.signal.aborted) {
        return { ok: false, reason: 'UNAVAILABLE' };
      }
      pieces = fresh?.pieces ?? pieces;
    }
    this.loading = null;
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

  /**
   * F-248 / SF-248-04 — **montre un jeu de pancartes** (mode `cards`, D1, D5). Le même `responseId` met le jeu à jour
   * (pancarte citée qui s'ajoute) sans recompter ; un nouveau le remplace. Une galerie **demandée** ouverte n'est
   * jamais remplacée : les pancartes attendent derrière la pastille (`pendingCards`). Rien en présentation.
   */
  showCards(responseId: string, cards: readonly JarvisCard[],
    opts: { flat?: boolean; now?: number; note?: string | null } = {}): PieceStoreResult {
    const now = opts.now ?? Date.now();
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    if (!cards.length) {
      return { ok: false, reason: 'NOTHING' };
    }
    const nav = this.nav();
    const asked = this.open() && this.mode() !== 'cards' && nav.origin === 'ASKED';
    if (asked) {
      this.pendingCards.set([...cards]);
      return { ok: false, reason: 'BUSY' };
    }
    this.cancelLinger();
    const fresh = responseId !== this.deckId || this.mode() !== 'cards';
    const ordered = orderCards(cards);
    const map = new Map<string, JarvisCard>();
    for (const card of ordered) {
      map.set(card.id, card);
    }
    const selectedId = !fresh && nav.selected != null ? nav.items[nav.selected - 1]?.id ?? null : null;
    this.cards.set(map);
    this.deckNote.set(opts.note ?? null);
    this.pieces.set(new Map(ordered.map((c) => [c.id, cardPiece(c)])));
    this.mode.set('cards');
    if (fresh) {
      this.deckPage.set(0);
    }
    this.pendingCards.set([]);
    // Le réducteur sans la mesure des pièces : un jeu de pancartes se compte en `c_*`, pas en galerie (`p_*`).
    const result = this.applyQuietly({ type: 'openGallery', scope: cardsScope(ordered.length),
      pieces: ordered.map(cardPiece), origin: 'PROACTIVE' });
    if (selectedId) {
      const index = ordered.findIndex((c) => c.id === selectedId);
      if (index >= 0) {
        this.applyQuietly({ type: 'select', ref: index + 1 });
      }
    }
    if (fresh && result.ok) {
      this.deckId = responseId;
      this.deckShownAt = now;
      this.count('c_set');
      this.count(sizeKey(ordered.length));
      this.count('c_old', ordered.filter((c) => cardFreshness(c.observedAt, now, false).old).length);
      if (opts.flat) {
        this.count('c_2d');
      }
    }
    return result;
  }

  /** F-248 / SF-248-04 — la pastille touchée : le jeu en attente remplace la galerie demandée. */
  openPendingCards(responseId: string, opts: { flat?: boolean; now?: number } = {}): PieceStoreResult {
    const pending = this.pendingCards();
    if (!pending.length) {
      return { ok: false, reason: 'NOTHING' };
    }
    this.mode.set('cards');
    return this.showCards(responseId, pending, opts);
  }

  /** F-248 / SF-248-04 — « et N autres » / « la suite » : la page suivante du mur (revient au début après la dernière). */
  nextDeckPage(): boolean {
    const deck = this.deck();
    const page = wallPage(deck, this.deckPage());
    if (page.pages <= 1) {
      return false;
    }
    this.deckPage.set(page.page + 1 >= page.pages ? 0 : page.page + 1);
    this.count('c_more');
    return true;
  }

  /** F-248 / SF-248-04 (D11) — une réponse sans pancarte : le jeu reste 60 s, puis s'efface. */
  lingerCards(ms = LINGER_MS): void {
    if (this.mode() !== 'cards' || !this.open()) {
      return;
    }
    this.cancelLinger();
    this.lingerTimer = setTimeout(() => {
      this.lingerTimer = null;
      if (this.mode() === 'cards') {
        this.closeCards();
      }
    }, ms);
  }

  /** F-248 / SF-248-04 — efface le jeu de pancartes (changement de sujet, « ferme ça ») ; une autre galerie reste. */
  closeCards(): void {
    this.cancelLinger();
    this.pendingCards.set([]);
    if (this.mode() !== 'cards') {
      return;
    }
    this.nav.set(initialNav());
    this.pieces.set(new Map());
    this.cards.set(new Map());
    this.deckNote.set(null);
    this.mode.set('fan');
    this.deckId = null;
  }

  private applyQuietly(action: NavAction): PieceStoreResult {
    const result = reduceNav(this.nav(), action);
    if (result.ok) {
      this.nav.set(result.state);
    }
    return result.ok ? { ok: true } : { ok: false, reason: result.reason };
  }

  private cancelLinger(): void {
    if (this.lingerTimer) {
      clearTimeout(this.lingerTimer);
      this.lingerTimer = null;
    }
  }

  /** Tout ferme (sortie de l'écran, bascule en présentation). */
  reset(): void {
    this.loading?.abort();
    this.loading = null;
    this.nav.set(initialNav());
    this.pieces.set(new Map());
    this.mode.set('fan');
    this.cards.set(new Map());
    this.pendingCards.set([]);
    this.cancelLinger();
    this.deckId = null;
  }
}

/** F-238 / SF-238-14 — la clé de mesure d'une opération d'architecture (aucun contenu). */
export function archKey(op: ArchOp): PieceCountKey | null {
  switch (op.kind) {
    case 'explode':
      return op.on ? 'a_exp' : null;
    case 'fold':
      return 'a_fold';
    case 'unfold':
      return 'a_unf';
    case 'focus':
      return op.ref ? 'a_foc' : null;
    case 'flow':
      return 'a_flow';
    case 'view':
      return 'a_view';
    case 'flat':
      return op.on ? 'a_2d_m' : null;
    default:
      return null;
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
    case 'AMBIGUOUS':
      return 'Il y en a plusieurs ; lequel voulez-vous ?';
    case 'PRESENTATION':
      return 'Pas de pièce en mode présentation.';
    case 'NOT_IN_SCOPE':
      return 'Cette pièce n’est pas du sujet en cours.';
    case 'RECENT':
    case 'NOTHING':
    case 'BUSY':
      return '';
    default:
      return 'Je n’arrive pas à lire les pièces pour le moment.';
  }
}

/**
 * F-263 / SF-263-02 — la correction dite quand l'écran refuse un ordre du cerveau rapide, qui a souvent déjà dit
 * « Voici » : elle commence par « Pardon, » ; une pièce introuvable nomme le sujet en vue (un mauvais sujet se
 * remarque). Refus silencieux (déjà montrée, occupé) : vide.
 */
export function showCorrection(reason: PieceStoreReason | undefined, subjectName: string | null): string {
  switch (reason) {
    case 'RECENT':
    case 'BUSY':
      return '';
    case 'NOTHING':
      return 'Pardon, il n’y a rien à montrer.';
    case 'NOT_FOUND':
      return subjectName ? `Pardon, je ne les trouve pas dans ${subjectName}.` : 'Pardon, je ne les trouve pas.';
    default: {
      const phrase = refusalPhrase(reason);
      return phrase ? `Pardon, ${phrase.charAt(0).toLowerCase()}${phrase.slice(1)}` : '';
    }
  }
}

/**
 * F-263 / SF-263-02 — des pièces montrées qui sont **toutes** sur un poste éteint : la phrase qui le dit (le « Voilà »
 * du cerveau ne doit pas laisser croire qu'elles s'ouvrent). Vide si une pièce au moins est en ligne, ou s'il n'y en a
 * aucune.
 */
export function offlinePiecesPhrase(pieces: readonly Piece[], hostName: string | null): string {
  if (!pieces.length || pieces.some((p) => p.available !== 'HOST_OFFLINE')) {
    return '';
  }
  return hostName ? `Elles sont sur le poste ${hostName}, éteint : je ne peux pas les ouvrir.`
    : 'Elles sont sur un poste éteint : je ne peux pas les ouvrir.';
}

/** F-272 / SF-272-04 — la pièce est dans la liste (version comprise ou non : « page:e2@v3 » ≈ « page:e2 »). */
export function hasPiece(pieces: readonly Piece[], id: string): boolean {
  const base = id.split('@')[0];
  return pieces.some((p) => p.id === id || p.id.split('@')[0] === base);
}
