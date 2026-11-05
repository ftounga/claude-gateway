import { NavScope } from '../pieces/jarvis-piece-nav';
import { Piece } from '../pieces/jarvis-piece.models';
import { PieceCountKey } from '../pieces/jarvis-piece-measure';
import { JarvisCard } from './jarvis-card.models';
import { toneOf } from './jarvis-card-templates';

/**
 * F-248 / SF-248-04 — **le jeu de pancartes** (cadrage §4.4, D5) : pur. Une pancarte ⇒ un **bloc au centre** (le héros
 * F-237, échelle 1,6) ; 2 à 6 ⇒ un **éventail** dont l'arc suit le nombre (2 → 40°, 6 → 110°) ; 7 et plus ⇒ un **mur**
 * de 6 (3 × 2) paginé, dernière case « et N autres ». Regroupement par client s'il y en a plusieurs, sinon par statut
 * (alerte, puis attend, puis le reste).
 */

export type DeckLayout = 'center' | 'fan' | 'wall';

/** Cases d'une page du mur. */
export const WALL_SIZE = 6;
/** Pancartes animées à la fois, au plus (entrée décalée de 60 ms). */
export const MAX_ANIMATED = 6;
export const ENTRY_STAGGER_MS = 60;
/** Une réponse sans pancarte laisse le jeu 60 s (D11). */
export const LINGER_MS = 60_000;
/** Rayon de l'éventail (px), comme la galerie F-237. */
const RADIUS_PX = 900;

export function deckLayout(count: number): DeckLayout {
  return count <= 1 ? 'center' : count <= 6 ? 'fan' : 'wall';
}

/** L'arc de l'éventail pour `count` pancartes : 2 → 40°, 6 → 110° (linéaire entre les deux). */
export function deckArc(count: number): number {
  if (count <= 1) {
    return 0;
  }
  const n = Math.min(6, count);
  return Math.round(40 + ((n - 2) * 70) / 4);
}

export interface DeckSlot {
  /** Rang dans le jeu ordonné (1..n). */
  index: number;
  x: number;
  y: number;
  z: number;
  angle: number;
  scale: number;
}

/** La position de chaque pancarte de la page affichée (éventail ou centre ; le mur est une grille CSS). */
export function deckSlots(count: number): DeckSlot[] {
  if (count <= 0) {
    return [];
  }
  if (count === 1) {
    return [{ index: 1, x: 0, y: 0, z: 120, angle: 0, scale: 1.6 }];
  }
  const arc = deckArc(count);
  const step = arc / (count - 1);
  const out: DeckSlot[] = [];
  for (let i = 0; i < count; i++) {
    const angle = -arc / 2 + i * step;
    const rad = (angle * Math.PI) / 180;
    out.push({
      index: i + 1, angle: Math.round(angle * 100) / 100, y: 0, scale: 1,
      x: Math.round(Math.sin(rad) * RADIUS_PX * 0.6),
      z: Math.round((Math.cos(rad) - 1) * RADIUS_PX * 0.4) + (i % 2 === 0 ? 0 : -40),
    });
  }
  return out;
}

const TONE_RANK: Record<string, number> = { alerte: 0, attend: 1 };

/**
 * L'ordre du jeu : par client quand il y en a plusieurs (ordre de première apparition), sinon par statut (alerte,
 * attend, le reste) ; l'ordre de lecture est gardé à égalité.
 */
export function orderCards(cards: readonly JarvisCard[]): JarvisCard[] {
  const groups = [...new Set(cards.map((c) => c.group ?? ''))];
  const indexed = cards.map((card, i) => ({ card, i }));
  if (groups.length > 1) {
    return indexed.sort((a, b) => groups.indexOf(a.card.group ?? '') - groups.indexOf(b.card.group ?? '')
      || (TONE_RANK[toneOf(a.card)] ?? 2) - (TONE_RANK[toneOf(b.card)] ?? 2) || a.i - b.i).map((x) => x.card);
  }
  return indexed.sort((a, b) => (TONE_RANK[toneOf(a.card)] ?? 2) - (TONE_RANK[toneOf(b.card)] ?? 2) || a.i - b.i)
    .map((x) => x.card);
}

/** L'en-tête de groupe d'une pancarte (un mot écrit, jamais une couleur seule), ou `null` s'il n'y a qu'un groupe. */
export function groupLabel(cards: readonly JarvisCard[], card: JarvisCard): string | null {
  const groups = new Set(cards.map((c) => c.group ?? ''));
  if (groups.size > 1) {
    return card.group || 'Autres';
  }
  return null;
}

/** Une page du mur : ses pancartes et le reste à annoncer (« et N autres » prend la dernière case). */
export interface WallPage {
  cards: JarvisCard[];
  /** Pancartes au-delà de cette page (0 = dernière page). */
  more: number;
  page: number;
  pages: number;
}

/** La page `page` (0..) du mur : 6 cases, la dernière devient « et N autres » s'il en reste. */
export function wallPage(cards: readonly JarvisCard[], page: number): WallPage {
  const perPage = WALL_SIZE - 1;
  if (cards.length <= WALL_SIZE) {
    return { cards: [...cards], more: 0, page: 0, pages: 1 };
  }
  const pages = Math.ceil((cards.length - 1) / perPage);
  const p = Math.max(0, Math.min(page, pages - 1));
  const start = p * perPage;
  const rest = cards.length - start;
  if (rest <= WALL_SIZE) {
    return { cards: cards.slice(start), more: 0, page: p, pages };
  }
  return { cards: cards.slice(start, start + perPage), more: rest - perPage, page: p, pages };
}

/** La clé de mesure de la taille d'un jeu. */
export function sizeKey(count: number): PieceCountKey {
  return count <= 1 ? 'c_n1' : count <= 6 ? 'c_n2_6' : 'c_n7p';
}

/** Une fiche en pièce de la galerie (type `FAIT`, D1) : la galerie, son réducteur et sa voix locale s'en servent. */
export function cardPiece(card: JarvisCard): Piece {
  return {
    id: card.id, category: 'ENTREE', kind: 'FAIT', title: card.title, at: card.observedAt ?? null,
    caption: card.status?.word ?? null, available: 'ONLINE',
  };
}

/** La portée de la galerie en mode pancartes. */
export function cardsScope(count: number): NavScope {
  return { subjectId: null, label: count > 1 ? `${count} pancartes` : 'Pancarte' };
}

/** Le jeu se dessine-t-il à plat (repli 2D, téléphone, qualité Basse, D7/D13) ? */
export function flatDeck(opts: { threeD: boolean; sober: boolean; reducedMotion: boolean; phone: boolean;
  lowQuality: boolean }): boolean {
  return opts.phone || !opts.threeD || opts.sober || opts.reducedMotion || opts.lowQuality;
}

const SETTING_KEY = 'jarvis.cards';

/** Le réglage « Pancartes » de l'appareil (D12, défaut oui) ; illisible ⇒ oui. */
export function cardsAllowedOnDevice(storage: Pick<Storage, 'getItem'> | null = safeStorage()): boolean {
  try {
    return storage?.getItem(SETTING_KEY) !== 'off';
  } catch {
    return true;
  }
}

/** Pose le réglage de l'appareil ; jamais d'exception. */
export function setCardsAllowedOnDevice(on: boolean, storage: Pick<Storage, 'setItem'> | null = safeStorage()): void {
  try {
    storage?.setItem(SETTING_KEY, on ? 'on' : 'off');
  } catch {
    // stockage indisponible : le réglage vit le temps de la page
  }
}

function safeStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
