/**
 * F-237 — les pièces que Jarvis peut montrer. Contrat importé de SF-237-01 (`GET /api/jarvis/pieces`, cadrage §4.1).
 */

export type PieceCategory = 'ENTREE' | 'PREUVE' | 'PRODUCTION';

export type PieceKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'PDF' | 'PAGE' | 'SLIDES' | 'ARCHITECTURE' | 'DIAGRAM' | 'TEXT'
  | 'FILE';

/** Tous les types, dans l'ordre — la garde du registre des visionneuses les parcourt. */
export const PIECE_KINDS: readonly PieceKind[] = [
  'IMAGE', 'VIDEO', 'AUDIO', 'PDF', 'PAGE', 'SLIDES', 'ARCHITECTURE', 'DIAGRAM', 'TEXT', 'FILE',
];

export type CaptionSource = 'TITLE' | 'DESCRIPTION' | 'MESSAGE' | 'STEP' | 'CHAPTERS' | 'REFORMULATION' | 'SUBJECT'
  | 'PROMPT' | 'SPEC';

export type ChantierLink = 'MESSAGE' | 'WINDOW' | 'NONE';

export type PieceAvailability = 'ONLINE' | 'HOST_OFFLINE' | 'PURGED';

export interface PieceChapter {
  t: number;
  title: string;
}

export interface PieceMeta {
  durationS?: number;
  chapters?: PieceChapter[];
  pages?: number;
  slides?: number;
  step?: string;
  series?: string;
  seriesTitle?: string;
  episode?: number;
  width?: number;
  height?: number;
  sizeBytes?: number;
  version?: number;
  [key: string]: unknown;
}

/** Une pièce, telle que la passerelle la rend — jamais de binaire, jamais d'URL S3. */
export interface Piece {
  id: string;
  category: PieceCategory;
  kind: PieceKind;
  title: string;
  caption?: string | null;
  captionSource?: CaptionSource | null;
  at?: string | null;
  subjectId?: string | null;
  chantier?: number | null;
  chantierLink?: ChantierLink;
  thumb?: string | null;
  open?: string | null;
  meta?: PieceMeta | null;
  available?: PieceAvailability;
}

export interface PieceListResponse {
  pieces: Piece[];
  total: number;
  truncated: boolean;
}
