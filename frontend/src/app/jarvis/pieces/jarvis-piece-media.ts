import { JarvisState } from '../jarvis-orb.component';

/**
 * F-237 / SF-237-06 — le son des pièces et la voix de Jarvis (cadrage §8.5, D10, Q3 : baisser) et les adresses de
 * contenu des visionneuses. Pur.
 */

/** Volume d'une vidéo / d'un audio pendant que Jarvis parle (D10 : baissé à 15 %, pas coupé). */
export const DUCK_VOLUME = 0.15;
/** Rampe de la baisse (ms) et délai avant de remonter après la phrase (ms). */
export const DUCK_RAMP_MS = 200;
export const UNDUCK_DELAY_MS = 300;
/** Reprise automatique si le PO a parlé sans donner d'ordre (ms). */
export const RESUME_AFTER_MS = 1500;

/** Le volume visé selon l'état de Jarvis. */
export function duckTarget(state: JarvisState): number {
  return state === 'parole' ? DUCK_VOLUME : 1;
}

/** Le PO parle : la pièce se met en pause (elle reprend si aucun ordre n'arrive). */
export function shouldAutoPause(state: JarvisState): boolean {
  return state === 'ecoute';
}

/** Les volumes intermédiaires d'une rampe linéaire, un pas toutes les 20 ms. */
export function rampSteps(from: number, to: number, ms: number, stepMs = 20): number[] {
  const count = Math.max(1, Math.round(ms / stepMs));
  return Array.from({ length: count }, (_, i) => Math.round((from + ((to - from) * (i + 1)) / count) * 1000) / 1000);
}

/** `video:<uuid>@v3` ⇒ la fiche de lecture (ticket du média) : `/videos/<uuid>?version=3`. */
export function videoInfoPath(pieceId: string): string | null {
  const match = /^video:([0-9a-f-]{36})(?:@v(\d+))?$/i.exec(pieceId);
  if (!match) {
    return null;
  }
  return match[2] ? `/videos/${match[1]}?version=${match[2]}` : `/videos/${match[1]}`;
}

/** `slides:<uuid>` et n ⇒ la diapo n (PNG). */
export function slidePath(pieceId: string, page: number): string | null {
  const match = /^slides:([0-9a-f-]{36})$/i.exec(pieceId);
  return match && page >= 1 ? `/presentations/${match[1]}/slides/${page}` : null;
}

/** L'adresse d'un PDF lu par le lecteur natif du navigateur, ouvert à la page n. */
export function pdfAt(objectUrl: string, page: number | null): string {
  return `${objectUrl}#page=${Math.max(1, page ?? 1)}`;
}

/** mm:ss. */
export function clockOf(seconds: number | null | undefined): string {
  const s = Math.max(0, Math.floor(seconds ?? 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
