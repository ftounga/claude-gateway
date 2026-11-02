import { Piece } from './jarvis-piece.models';

/**
 * F-237 / SF-237-11 — les versions et le rideau de l'avant / après (pur).
 */

/** Une version d'une page ou d'une vidéo absente de l'index (qui ne tient que la courante) : `null` si sans objet. */
export function versionPiece(base: Piece, wanted: string): Piece | null {
  const m = /^(page|video):([0-9a-f-]{36})@v(\d{1,4})$/.exec(wanted);
  const b = /^(page|video):([0-9a-f-]{36})@v\d+$/.exec(base.id);
  if (!m || !b || m[1] !== b[1] || m[2] !== b[2]) {
    return null;
  }
  const n = Number(m[3]);
  if (n < 1) {
    return null;
  }
  const open = m[1] === 'page' ? `/pages/${m[2]}?version=${n}` : `/videos/${m[2]}/versions/${n}/media`;
  return {
    ...base, id: wanted, title: `${base.title} (v${n})`, open, thumb: m[1] === 'page' ? null : base.thumb,
    meta: { ...(base.meta ?? {}), version: n },
  };
}

/** Le rideau : la part de l'« après » visible (0..100), bornée. */
export function curtain(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : 50;
}
