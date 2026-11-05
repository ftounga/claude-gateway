import { PIECE_KINDS, PieceKind } from './jarvis-piece.models';

/**
 * F-237 / SF-237-04 — le **registre des visionneuses** (cadrage §8.2, §10 point 2) : une entrée par type de pièce,
 * même principe que `SCENES_3D`. Ce que le réducteur de navigation consulte (pages ? temps ? zoom ?) ; les composants
 * visuels s'y branchent en SF-237-06 / 07. Une visionneuse peut être **remplacée** (point d'extension de l'architecture
 * 3D) sans toucher au reste.
 */
export interface PieceViewer {
  /** Nom de la visionneuse (diagnostic). */
  name: string;
  /** Feuilletable : pages d'un PDF, diapos, sections d'une page. */
  paged: boolean;
  /** Temporelle : lecture, pause, aller à, chapitres. */
  timed: boolean;
  /** Zoomable et déplaçable. */
  zoomable: boolean;
}

const FICHE: PieceViewer = { name: 'fiche', paged: false, timed: false, zoomable: false };

const DEFAULT_VIEWERS: Record<PieceKind, PieceViewer> = {
  IMAGE: { name: 'image', paged: false, timed: false, zoomable: true },
  VIDEO: { name: 'video', paged: false, timed: true, zoomable: false },
  AUDIO: { name: 'audio', paged: false, timed: true, zoomable: false },
  PDF: { name: 'pdf', paged: true, timed: false, zoomable: true },
  PAGE: { name: 'page', paged: true, timed: false, zoomable: false },
  SLIDES: { name: 'diapos', paged: true, timed: false, zoomable: true },
  ARCHITECTURE: { name: 'schema-svg', paged: false, timed: false, zoomable: true },
  DIAGRAM: { name: 'schema-svg', paged: false, timed: false, zoomable: true },
  TEXT: { name: 'texte', paged: false, timed: false, zoomable: false },
  FILE: FICHE,
  // F-248 / SF-248-04 (D1) — la visionneuse des faits : la pancarte (zoomable, plein écran en SF-248-06).
  FAIT: { name: 'pancarte', paged: false, timed: false, zoomable: true },
};

/** Le registre courant (modifiable par {@link registerViewer}). */
export const PIECE_VIEWERS: Record<PieceKind, PieceViewer> = { ...DEFAULT_VIEWERS };

/** La visionneuse d'un type — la fiche FILE pour un type inconnu, jamais rien. */
export function viewerFor(kind: PieceKind | string | null | undefined): PieceViewer {
  return (kind && (PIECE_VIEWERS as Record<string, PieceViewer>)[kind]) || FICHE;
}

/** Remplace la visionneuse d'un type (ex. : architecture en 3D) ; rend l'ancienne. */
export function registerViewer(kind: PieceKind, viewer: PieceViewer): PieceViewer {
  const previous = PIECE_VIEWERS[kind];
  PIECE_VIEWERS[kind] = viewer;
  return previous;
}

/** Remet le registre d'origine (tests). */
export function resetViewers(): void {
  for (const kind of PIECE_KINDS) {
    PIECE_VIEWERS[kind] = DEFAULT_VIEWERS[kind];
  }
}

/** La garde : les types sans visionneuse (doit être vide). */
export function kindsWithoutViewer(): PieceKind[] {
  return PIECE_KINDS.filter(kind => !PIECE_VIEWERS[kind]);
}
