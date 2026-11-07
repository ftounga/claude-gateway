/**
 * F-250 / SF-250-05 / SF-250-07 — **la place du visage dans la scène** (cadrage §5). Fichier <b>pur</b>.
 */

/** Hauteur du visage à l'échelle 1 (front → menton). */
const FACE_HEIGHT = 1.86;
/** Part de la hauteur de l'écran occupée par le visage (figure principale de `/jarvis`). */
export const FACE_SCREEN_SHARE = 0.5;
/** Au téléphone (portrait), part de la largeur au plus. */
const FACE_WIDTH_SHARE = 0.7;
/** Le visage n'est jamais plus petit que le cœur qu'il remplace. */
export const FACE_MIN_SCALE = 1.5;

/**
 * L'échelle du visage pour qu'il tienne {@link FACE_SCREEN_SHARE} de la hauteur de l'écran à la distance `distance`
 * de la caméra (champ vertical `fovDeg`, rapport `aspect` = largeur / hauteur), sans déborder en largeur au
 * téléphone ; jamais sous la taille du cœur.
 */
export function faceScaleFor(distance: number, aspect: number, fovDeg: number): number {
  const visibleH = 2 * Math.max(distance, 0.5) * Math.tan((fovDeg * Math.PI) / 360);
  const byHeight = (FACE_SCREEN_SHARE * visibleH) / FACE_HEIGHT;
  const byWidth = (FACE_WIDTH_SHARE * visibleH * Math.max(aspect, 0.2)) / 1.52;
  return Math.max(FACE_MIN_SCALE, Math.min(byHeight, byWidth));
}
