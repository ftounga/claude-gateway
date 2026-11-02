import { normalize } from '../jarvis-oral';
import { Piece, PieceCategory, PieceChapter, PieceKind } from './jarvis-piece.models';
import { viewerFor } from './jarvis-piece-viewers';

/**
 * F-237 / SF-237-04 — **l'état de navigation** parmi les pièces (cadrage §5.1) : une seule source de vérité, un
 * réducteur **pur**. Le toucher, la grammaire vocale locale et l'outil `afficher` du cerveau rapide envoient les
 * **mêmes** actions ; une action invalide laisse l'état inchangé et rend sa raison (pour que Jarvis la dise).
 */

export type NavLevel = 'UNIVERS' | 'CHANTIER' | 'GALERIE' | 'PIECE' | 'PAGE' | 'INSTANT';

export type NavOrigin = 'ASKED' | 'PROACTIVE';

export type NavReason = 'OUT_OF_RANGE' | 'NOT_APPLICABLE' | 'NO_GALLERY' | 'NOT_FOUND';

/** Cartes chargées au plus ; au-delà, « +n ». */
export const MAX_NAV_ITEMS = 24;
/** Bornes du zoom. */
export const MIN_ZOOM = 1;
export const MAX_ZOOM = 8;
/** Profondeur de la pile d'historique (fil d'Ariane). */
export const MAX_HISTORY = 8;

export interface NavScope {
  subjectId: string | null;
  chantier?: number | null;
  category?: PieceCategory | null;
  kind?: PieceKind | null;
  series?: string | null;
  /** « Entrées du chantier 3 — Edenred ». */
  label: string;
}

export interface NavItem {
  /** 1..n — l'index que le PO dit (« la deuxième »). */
  index: number;
  id: string;
  kind: PieceKind;
  title: string;
  at?: string | null;
  pages?: number | null;
  durationS?: number | null;
  chapters?: PieceChapter[];
}

export interface PieceNav {
  level: NavLevel;
  scope: NavScope | null;
  items: NavItem[];
  /** Pièces au-delà des cartes chargées. */
  more: number;
  selected: number | null;
  page: number | null;
  time: number | null;
  playing: boolean;
  chapter: number | null;
  zoom: number;
  pan: { x: number; y: number };
  /** Cible nommée (« le VPC de prod ») — point d'extension §10. */
  target: string | null;
  fullscreen: boolean;
  origin: NavOrigin;
  /** Niveaux précédents (fil d'Ariane), ≤ 8. */
  history: NavLevel[];
}

export type NavAction =
  | { type: 'openGallery'; scope: NavScope; pieces: readonly Piece[]; origin?: NavOrigin }
  | { type: 'select'; ref: number | string }
  | { type: 'next' }
  | { type: 'prev' }
  | { type: 'back' }
  | { type: 'up' }
  | { type: 'close' }
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'seek'; t?: number; chapter?: number | string }
  | { type: 'page'; n?: number; delta?: number }
  | { type: 'zoom'; step?: '+' | '-' | 'reset'; value?: number; target?: string }
  | { type: 'pan'; dx: number; dy: number }
  | { type: 'fullscreen' };

export interface NavResult {
  state: PieceNav;
  ok: boolean;
  reason?: NavReason;
}

/** L'univers, sans galerie. */
export function initialNav(): PieceNav {
  return {
    level: 'UNIVERS', scope: null, items: [], more: 0, selected: null, page: null, time: null, playing: false,
    chapter: null, zoom: 1, pan: { x: 0, y: 0 }, target: null, fullscreen: false, origin: 'ASKED', history: [],
  };
}

/** Une pièce de l'index en carte de navigation. */
export function toNavItem(piece: Piece, index: number): NavItem {
  const meta = piece.meta ?? {};
  return {
    index, id: piece.id, kind: piece.kind, title: piece.title, at: piece.at ?? null,
    pages: meta.pages ?? meta.slides ?? null, durationS: meta.durationS ?? null, chapters: meta.chapters ?? [],
  };
}

function ok(state: PieceNav): NavResult {
  return { state, ok: true };
}

function refuse(state: PieceNav, reason: NavReason): NavResult {
  return { state, ok: false, reason };
}

function pushHistory(history: NavLevel[], level: NavLevel): NavLevel[] {
  const next = [...history, level];
  return next.length > MAX_HISTORY ? next.slice(next.length - MAX_HISTORY) : next;
}

function go(state: PieceNav, patch: Partial<PieceNav>): PieceNav {
  const level = patch.level ?? state.level;
  const history = level !== state.level ? pushHistory(state.history, state.level) : state.history;
  return { ...state, ...patch, history };
}

/** La pièce sélectionnée, s'il y en a une. */
export function selectedItem(state: PieceNav): NavItem | null {
  return state.selected == null ? null : state.items[state.selected - 1] ?? null;
}

/** Ouvrir la pièce n° `index` : héros, lecture lancée pour un média, page 1 pour un document. */
function openItem(state: PieceNav, index: number): NavResult {
  if (!state.items.length) {
    return refuse(state, 'NO_GALLERY');
  }
  if (!Number.isInteger(index) || index < 1 || index > state.items.length) {
    return refuse(state, 'OUT_OF_RANGE');
  }
  const viewer = viewerFor(state.items[index - 1].kind);
  return ok(go(state, {
    level: 'PIECE', selected: index, page: viewer.paged ? 1 : null, time: viewer.timed ? 0 : null,
    playing: viewer.timed, chapter: null, zoom: 1, pan: { x: 0, y: 0 }, target: null,
  }));
}

/** « celle sur le déploiement » : l'identifiant exact, sinon le seul titre qui contient la phrase. */
function findByRef(items: readonly NavItem[], ref: string): number | null {
  const exact = items.find(item => item.id === ref);
  if (exact) {
    return exact.index;
  }
  const wanted = normalize(ref);
  if (!wanted) {
    return null;
  }
  const matches = items.filter(item => normalize(item.title).includes(wanted));
  return matches.length === 1 ? matches[0].index : null;
}

function findChapter(chapters: readonly PieceChapter[], ref: number | string): number | null {
  if (typeof ref === 'number') {
    return Number.isInteger(ref) && ref >= 1 && ref <= chapters.length ? ref : null;
  }
  const wanted = normalize(ref);
  const matches = chapters
    .map((chapter, i) => ({ i: i + 1, title: normalize(chapter.title) }))
    .filter(chapter => wanted && chapter.title.includes(wanted));
  return matches.length === 1 ? matches[0].i : null;
}

/** Le réducteur — pur : même état et même action ⇒ même résultat. */
export function reduceNav(state: PieceNav, action: NavAction): NavResult {
  const item = selectedItem(state);
  const viewer = item ? viewerFor(item.kind) : null;
  switch (action.type) {
    case 'openGallery': {
      const pieces = action.pieces.slice(0, MAX_NAV_ITEMS);
      return ok(go(state, {
        level: 'GALERIE', scope: action.scope, items: pieces.map((p, i) => toNavItem(p, i + 1)),
        more: Math.max(0, action.pieces.length - MAX_NAV_ITEMS), selected: null, page: null, time: null,
        playing: false, chapter: null, zoom: 1, pan: { x: 0, y: 0 }, target: null,
        origin: action.origin ?? 'ASKED',
      }));
    }
    case 'select': {
      if (!state.items.length) {
        return refuse(state, 'NO_GALLERY');
      }
      if (typeof action.ref === 'number') {
        return openItem(state, action.ref);
      }
      const index = findByRef(state.items, action.ref);
      return index == null ? refuse(state, 'NOT_FOUND') : openItem(state, index);
    }
    case 'next':
    case 'prev': {
      if (!state.items.length) {
        return refuse(state, 'NO_GALLERY');
      }
      if (state.selected == null) {
        return openItem(state, action.type === 'next' ? 1 : state.items.length);
      }
      return openItem(state, state.selected + (action.type === 'next' ? 1 : -1));
    }
    case 'back': {
      switch (state.level) {
        case 'INSTANT':
        case 'PAGE':
          return ok(go(state, { level: 'PIECE' }));
        case 'PIECE':
          return ok(go(state, {
            level: 'GALERIE', selected: null, page: null, time: null, playing: false, chapter: null, zoom: 1,
            pan: { x: 0, y: 0 }, target: null, fullscreen: false,
          }));
        case 'GALERIE':
          return reduceNav(state, { type: 'up' });
        case 'CHANTIER':
          return ok(initialNav());
        default:
          return refuse(state, 'NOT_APPLICABLE');
      }
    }
    case 'up': {
      if (state.level === 'UNIVERS') {
        return refuse(state, 'NOT_APPLICABLE');
      }
      const toChantier = state.scope?.chantier != null && state.level !== 'CHANTIER';
      return ok(go(state, {
        level: toChantier ? 'CHANTIER' : 'UNIVERS', items: [], more: 0, selected: null, page: null, time: null,
        playing: false, chapter: null, zoom: 1, pan: { x: 0, y: 0 }, target: null, fullscreen: false,
        scope: toChantier ? state.scope : null,
      }));
    }
    case 'close':
      return ok(initialNav());
    case 'play':
    case 'pause': {
      if (!viewer?.timed) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      return ok({ ...state, playing: action.type === 'play' });
    }
    case 'seek': {
      if (!item || !viewer?.timed) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      const chapters = item.chapters ?? [];
      if (action.chapter != null) {
        const chapter = findChapter(chapters, action.chapter);
        if (chapter == null) {
          return refuse(state, typeof action.chapter === 'number' ? 'OUT_OF_RANGE' : 'NOT_FOUND');
        }
        return ok(go(state, { level: 'INSTANT', chapter, time: chapters[chapter - 1].t, playing: true }));
      }
      const t = action.t ?? 0;
      if (t < 0 || (item.durationS != null && t > item.durationS)) {
        return refuse(state, 'OUT_OF_RANGE');
      }
      return ok(go(state, { level: 'INSTANT', time: t, chapter: chapterAt(chapters, t) }));
    }
    case 'page': {
      if (!item || !viewer?.paged) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      const target = action.n ?? (state.page ?? 1) + (action.delta ?? 1);
      if (!Number.isInteger(target) || target < 1 || (item.pages != null && target > item.pages)) {
        return refuse(state, 'OUT_OF_RANGE');
      }
      return ok(go(state, { level: target === 1 && state.level === 'PIECE' ? 'PIECE' : 'PAGE', page: target }));
    }
    case 'zoom': {
      if (!item || !viewer?.zoomable) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      let zoom = state.zoom;
      if (action.value != null) {
        if (action.value < MIN_ZOOM || action.value > MAX_ZOOM) {
          return refuse(state, 'OUT_OF_RANGE');
        }
        zoom = action.value;
      } else if (action.step === 'reset') {
        zoom = MIN_ZOOM;
      } else if (action.step === '-') {
        zoom = Math.max(MIN_ZOOM, zoom / 2);
      } else if (action.step === '+' || action.target) {
        zoom = Math.min(MAX_ZOOM, zoom * 2);
      }
      const reset = zoom === MIN_ZOOM;
      return ok({
        ...state, zoom, pan: reset ? { x: 0, y: 0 } : state.pan,
        target: reset ? null : action.target ?? state.target,
      });
    }
    case 'pan': {
      if (!item || !viewer?.zoomable || state.zoom <= MIN_ZOOM) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      return ok({ ...state, pan: { x: state.pan.x + action.dx, y: state.pan.y + action.dy } });
    }
    case 'fullscreen':
      if (!item) {
        return refuse(state, 'NOT_APPLICABLE');
      }
      return ok({ ...state, fullscreen: !state.fullscreen });
  }
}

/** Le chapitre (1..n) en cours au temps `t`, `null` sans chapitres. */
export function chapterAt(chapters: readonly PieceChapter[], t: number): number | null {
  let found: number | null = null;
  chapters.forEach((chapter, i) => {
    if (chapter.t <= t) {
      found = i + 1;
    }
  });
  return found;
}
