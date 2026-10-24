import { VideoComment, VideoCommentStatus } from '../../core/models/videos.models';

/**
 * **Les retours à la seconde** (F-214 / SF-214-02) — les calculs purs de l'écran : étiquettes d'instant, place
 * des marqueurs, libellés de statut, brouillon composé. Mêmes règles que la gateway (30 i/s, `mm:ss`).
 */

/** Cadence retenue pour l'image d'un instant — la même que la gateway (F-213 ne range pas la cadence). */
export const REVIEW_FPS = 30;

/** Longueur maximale d'un retour (borne de la gateway). */
export const REVIEW_MAX_CHARS = 1000;

/** L'image d'un instant (en ms), arrondie. */
export function frameAt(ms: number): number {
  return Math.round((Math.max(0, ms) * REVIEW_FPS) / 1000);
}

/** `00:10`, ou `1:02:03` au-delà d'une heure (secondes tronquées, comme la gateway). */
export function reviewClock(ms: number): string {
  const total = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/** L'étiquette du champ : `00:10 · image 304`, ou `01:20 → 01:35 · images 2400–2850`. */
export function instantLabel(startMs: number, endMs: number | null): string {
  if (endMs == null) {
    return `${reviewClock(startMs)} · image ${frameAt(startMs)}`;
  }
  return `${reviewClock(startMs)} → ${reviewClock(endMs)} · images ${frameAt(startMs)}–${frameAt(endMs)}`;
}

/** L'étiquette d'un retour rangé. */
export function commentInstant(comment: VideoComment): string {
  return instantLabel(comment.tStartMs, comment.tEndMs);
}

/** Le libellé du statut, par version : « Traité en v2 », « Rejeté en v2 — motif ». */
export function statusLabel(comment: VideoComment): string {
  switch (comment.status) {
    case 'OUVERT':
      return 'Ouvert';
    case 'ENVOYE':
      return 'Envoyé à l\'agent';
    case 'TRAITE':
      return comment.resolvedVersion ? `Traité en v${comment.resolvedVersion}` : 'Traité';
    case 'REJETE': {
      const base = comment.resolvedVersion ? `Rejeté en v${comment.resolvedVersion}` : 'Rejeté';
      return comment.resolution ? `${base} — ${comment.resolution}` : base;
    }
  }
}

/** Le modificateur CSS d'un statut. */
export function statusModifier(status: VideoCommentStatus): string {
  return status.toLowerCase();
}

/** Un marqueur sur la barre des retours : position et largeur en % de la durée. */
export interface ReviewMarker {
  id: string;
  left: number;
  width: number;
  status: VideoCommentStatus;
  label: string;
  startMs: number;
}

/** Les marqueurs des retours d'une version, placés à `t / durée` (bornés à la barre). */
export function markersOf(comments: VideoComment[], durationS: number | null | undefined): ReviewMarker[] {
  if (!durationS || !(durationS > 0)) {
    return [];
  }
  const totalMs = durationS * 1000;
  const percent = (ms: number) => Math.min(100, Math.max(0, (ms / totalMs) * 100));
  return comments.map((comment) => {
    const left = percent(comment.tStartMs);
    const width = comment.tEndMs == null ? 0 : Math.max(0, percent(comment.tEndMs) - left);
    return {
      id: comment.id,
      left,
      width,
      status: comment.status,
      label: `${commentInstant(comment)} — ${comment.text}`,
      startMs: comment.tStartMs,
    };
  });
}

/** Les retours d'une version, par instant. */
export function commentsOfVersion(comments: VideoComment[], version: number): VideoComment[] {
  return comments
    .filter((comment) => comment.version === version)
    .sort((a, b) => a.tStartMs - b.tStartMs || a.createdAt.localeCompare(b.createdAt));
}

/** Le brouillon : le message en tête, un brouillon déjà tapé conservé dessous. */
export function draftWithMessage(message: string, draft: string | null | undefined): string {
  const kept = (draft ?? '').trim();
  return kept.length > 0 ? `${message}\n\n${kept}` : message;
}

/** Vrai si la touche vient d'un champ de saisie (le raccourci `C` ne s'y applique pas). */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!target || typeof (target as HTMLElement).tagName !== 'string') {
    return false;
  }
  const element = target as HTMLElement;
  const tag = element.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || element.isContentEditable === true;
}
