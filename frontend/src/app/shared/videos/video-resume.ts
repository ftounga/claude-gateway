/**
 * **La reprise de lecture** (F-213 / SF-213-03) : la position d'une vidéo, gardée **sur cet appareil**
 * (`localStorage`, cadrage §6). Un stockage indisponible (navigation privée, données bloquées) ne casse rien : la
 * lecture repart du début.
 */

const PREFIX = 'cg.video.position.';

/** En dessous, rien n'est gardé : on vient à peine de commencer. */
export const RESUME_MIN_SECONDS = 5;

/** À moins de 5 s de la fin, la vidéo est vue : la position est effacée. */
export const RESUME_END_MARGIN_SECONDS = 5;

function key(videoId: string, version: number): string {
  return `${PREFIX}${videoId}.v${version}`;
}

/** La position gardée, ou `null`. */
export function readPosition(videoId: string, version: number): number | null {
  try {
    const raw = globalThis.localStorage?.getItem(key(videoId, version));
    const value = raw == null ? NaN : Number(raw);
    return Number.isFinite(value) && value >= RESUME_MIN_SECONDS ? value : null;
  } catch {
    return null;
  }
}

/** Garde la position ; l'efface au début ou près de la fin. */
export function savePosition(videoId: string, version: number, position: number, duration: number): void {
  try {
    const storage = globalThis.localStorage;
    if (!storage) {
      return;
    }
    const nearEnd = Number.isFinite(duration) && duration > 0 && position >= duration - RESUME_END_MARGIN_SECONDS;
    if (!Number.isFinite(position) || position < RESUME_MIN_SECONDS || nearEnd) {
      storage.removeItem(key(videoId, version));
    } else {
      storage.setItem(key(videoId, version), String(Math.floor(position)));
    }
  } catch {
    // Stockage indisponible : la reprise est un confort, jamais une condition.
  }
}

/** Efface la position (vidéo vue jusqu'au bout, ou supprimée). */
export function clearPosition(videoId: string, version: number): void {
  try {
    globalThis.localStorage?.removeItem(key(videoId, version));
  } catch {
    // idem
  }
}
