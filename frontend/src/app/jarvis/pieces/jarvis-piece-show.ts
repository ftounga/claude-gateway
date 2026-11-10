import { PieceQuery } from './jarvis-piece-client';
import { NavAction, NavOrigin, NavScope, PieceNav } from './jarvis-piece-nav';
import { PieceCategory, PieceKind } from './jarvis-piece.models';

/**
 * F-237 / SF-237-08 — **l'ordre d'écran du cerveau rapide** (outil `afficher`, événement SSE `show`) traduit en ce
 * que l'écran fait : ouvrir une galerie (lecture de l'index) ou une action du réducteur `PieceNav` — les **mêmes**
 * actions que le toucher et la voix locale. Pur : les garde-fous écran (cadrage §7) sont appliqués ici.
 */

/** Les actions de l'outil `afficher` (fermées, mêmes que la passerelle). */
export type ShowAction = 'galerie' | 'piece' | 'suivante' | 'precedente' | 'selection' | 'retour' | 'remonter'
  | 'fermer' | 'lecture' | 'pause' | 'aller' | 'page' | 'zoom' | 'comparer' | 'raconter' | 'planche'
  /** F-253 / SF-253-02 — une vue de l'univers : appliquée par le « Montrer » F-234, jamais par la galerie. */
  | 'univers';

export interface ShowScope {
  subjectId?: string | null;
  /** F-253 / SF-253-03 — le client (clé de l'univers) et son nom exact, rapprochés par la passerelle. */
  client?: string | null;
  clientName?: string | null;
  chantier?: number | null;
  category?: string | null;
  type?: string | null;
  series?: string | null;
  since?: string | null;
}

/** L'ordre tel que la passerelle le relaie (`JarvisShowCommand`). */
export interface ShowCommand {
  action: ShowAction;
  pieces?: string[];
  scope?: ShowScope | null;
  index?: number | null;
  time?: number | null;
  chapter?: string | null;
  page?: number | null;
  zoom?: string | null;
  target?: string | null;
  reason?: 'demande' | 'appui';
  /** F-253 / SF-253-02 — la vue de l'action `univers` (filtre fermé du « Montrer »). */
  view?: string | null;
  /** F-253 / SF-253-02 — le client qui restreint la vue (nom exact, rapproché par la passerelle). */
  client?: string | null;
}

/** Comment la galerie se présente : éventail normal, comparaison, récit, planche contact (SF-237-10/11). */
export type GalleryMode = 'fan' | 'compare' | 'story' | 'contact'
  /** F-248 / SF-248-04 (D1) — les pancartes : un jeu de faits lus. */
  | 'cards';

export type ShowRefusal = 'PRESENTATION' | 'NOT_IN_SCOPE' | 'RECENT' | 'NOTHING';

export type ShowPlan =
  | { kind: 'refuse'; reason: ShowRefusal }
  | {
    kind: 'gallery'; scope: NavScope; query: PieceQuery; ids: string[]; select: number | null; origin: NavOrigin;
    mode: GalleryMode;
  }
  | { kind: 'nav'; action: NavAction; origin: NavOrigin };

/** Ce que l'écran sait au moment de l'ordre. */
export interface ShowContext {
  subjectId: string | null;
  subjectName: string | null;
  presentation: boolean;
  sober: boolean;
  nav: PieceNav;
  /** Dernière fois qu'une pièce a été montrée d'initiative, par identifiant (ms). */
  recent: ReadonlyMap<string, number>;
  now: number;
}

/** Une pièce d'initiative déjà montrée il y a moins de 10 min n'est pas remontrée (§7). */
export const RECENT_MS = 10 * 60_000;

const CATEGORIES: Record<string, PieceCategory> = { entrees: 'ENTREE', preuves: 'PREUVE', productions: 'PRODUCTION' };
const KINDS: Record<string, PieceKind> = {
  image: 'IMAGE', video: 'VIDEO', audio: 'AUDIO', pdf: 'PDF', page: 'PAGE', architecture: 'ARCHITECTURE',
  schema: 'DIAGRAM', diaporama: 'SLIDES', texte: 'TEXT', fichier: 'FILE',
};
const CATEGORY_LABELS: Record<PieceCategory, string> = {
  ENTREE: 'Entrées', PREUVE: 'Preuves', PRODUCTION: 'Productions',
};

/** « Preuves du chantier 3 — Edenred ». */
export function scopeLabel(scope: ShowScope | null | undefined, subjectName: string | null, mode: GalleryMode): string {
  const category = scope?.category ? CATEGORIES[scope.category] : undefined;
  let head = mode === 'contact' ? 'Planche contact' : mode === 'story' ? 'Récit' : mode === 'compare' ? 'Avant / après'
    : category ? CATEGORY_LABELS[category] : 'Pièces';
  if (scope?.chantier != null) {
    head += ` du chantier ${scope.chantier}`;
  } else if (scope?.since) {
    head += ` depuis le ${scope.since}`;
  }
  return subjectName ? `${head} — ${subjectName}` : head;
}

const MODES: Partial<Record<ShowAction, GalleryMode>> = { comparer: 'compare', raconter: 'story', planche: 'contact' };

/** Le plan d'un ordre ; un ordre d'initiative passe d'abord les garde-fous écran. */
export function showPlan(cmd: ShowCommand, ctx: ShowContext): ShowPlan {
  const origin: NavOrigin = cmd.reason === 'appui' ? 'PROACTIVE' : 'ASKED';
  if (ctx.presentation && cmd.action !== 'fermer') {
    return { kind: 'refuse', reason: 'PRESENTATION' };
  }
  switch (cmd.action) {
    case 'galerie':
    case 'piece':
    case 'comparer':
    case 'raconter':
    case 'planche':
      return galleryPlan(cmd, ctx, origin);
    case 'suivante':
      return { kind: 'nav', action: { type: 'next' }, origin };
    case 'precedente':
      return { kind: 'nav', action: { type: 'prev' }, origin };
    case 'selection': {
      const ref = cmd.index ?? cmd.pieces?.[0] ?? cmd.target ?? null;
      return ref == null ? { kind: 'refuse', reason: 'NOTHING' } : { kind: 'nav', action: { type: 'select', ref }, origin };
    }
    case 'retour':
      return { kind: 'nav', action: { type: 'back' }, origin };
    case 'remonter':
      return { kind: 'nav', action: { type: 'up' }, origin };
    case 'fermer':
      return { kind: 'nav', action: { type: 'close' }, origin };
    case 'lecture':
      return { kind: 'nav', action: { type: 'play' }, origin };
    case 'pause':
      return { kind: 'nav', action: { type: 'pause' }, origin };
    case 'aller': {
      if (cmd.chapter) {
        const n = /^\d+$/.test(cmd.chapter) ? Number(cmd.chapter) : cmd.chapter;
        return { kind: 'nav', action: { type: 'seek', chapter: n }, origin };
      }
      return { kind: 'nav', action: { type: 'seek', t: cmd.time ?? 0 }, origin };
    }
    case 'page':
      return cmd.page == null ? { kind: 'nav', action: { type: 'page', delta: 1 }, origin }
        : { kind: 'nav', action: { type: 'page', n: cmd.page }, origin };
    case 'zoom': {
      if (origin === 'PROACTIVE' && ctx.sober) {
        return { kind: 'refuse', reason: 'NOTHING' }; // mode sobre : aucun zoom d'initiative (§7)
      }
      const z = cmd.zoom ?? '';
      if (/^[1-8]$/.test(z)) {
        return { kind: 'nav', action: { type: 'zoom', value: Number(z), target: cmd.target ?? undefined }, origin };
      }
      const step = z === '-' ? '-' : z === 'reset' ? 'reset' : '+';
      return { kind: 'nav', action: { type: 'zoom', step, target: cmd.target ?? undefined }, origin };
    }
  }
  return { kind: 'refuse', reason: 'NOTHING' };
}

function galleryPlan(cmd: ShowCommand, ctx: ShowContext, origin: NavOrigin): ShowPlan {
  const scope = cmd.scope ?? null;
  // F-253 / SF-253-03 — portée client : les sujets du client, sans le sujet courant.
  const client = !scope?.subjectId && scope?.client ? scope.client : null;
  const subjectId = scope?.subjectId || (client ? null : ctx.subjectId);
  if (origin === 'PROACTIVE' && (client || (scope?.subjectId && ctx.subjectId && scope.subjectId !== ctx.subjectId))) {
    return { kind: 'refuse', reason: 'NOT_IN_SCOPE' }; // d'initiative : seulement le sujet dont on parle
  }
  let ids = [...(cmd.pieces ?? [])];
  if (origin === 'PROACTIVE' && ids.length) {
    ids = ids.filter((id) => {
      const at = ctx.recent.get(id);
      return at == null || ctx.now - at >= RECENT_MS;
    });
    if (!ids.length) {
      return { kind: 'refuse', reason: 'RECENT' };
    }
  }
  if (!subjectId && !client && !ids.length && !scope?.since) {
    return { kind: 'refuse', reason: 'NOTHING' };
  }
  const mode: GalleryMode = MODES[cmd.action] ?? 'fan';
  const query: PieceQuery = {
    subjectId: subjectId ?? null,
    clientId: client,
    chantier: scope?.chantier ?? null,
    category: scope?.category ? CATEGORIES[scope.category] ?? null : null,
    kind: scope?.type ? KINDS[scope.type] ?? null : null,
    series: scope?.series ?? null,
    since: scope?.since ?? null,
    limit: ids.length ? 200 : 50,
  };
  const navScope: NavScope = {
    subjectId: subjectId ?? null, chantier: scope?.chantier ?? null, category: query.category ?? null,
    kind: query.kind ?? null, series: scope?.series ?? null, label: scopeLabel(scope, client ? scope?.clientName ?? null : ctx.subjectName, mode),
  };
  const select = cmd.action === 'piece' && ids.length === 1 ? 1 : cmd.index ?? null;
  return { kind: 'gallery', scope: navScope, query, ids, select, origin, mode };
}
