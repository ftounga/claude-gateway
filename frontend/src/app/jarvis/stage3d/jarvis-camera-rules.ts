/**
 * F-236 — **les règles de caméra** (cadrage §9.3). Fichier <b>pur</b>.
 *
 * <p>SF-236-04 : la vue d'ensemble (sphère englobante ajustée au plus petit champ, portrait compris). Focale fixe
 * 55°, aucun roulis.</p>
 */

/** Focale verticale fixe (§9.3). */
export const CAMERA_FOV = 55;
/** Élévation de la vue d'ensemble (T-Z2 : légère montée, 18°). */
export const OVERVIEW_ELEVATION_DEG = 18;
/** Distance minimale : le cœur et le grand cerveau tiennent toujours dans le champ. */
export const MIN_DISTANCE = 9;

/**
 * La distance qui fait tenir l'univers (demi-étendues) dans le champ, en largeur comme en hauteur, à `aspect`
 * (largeur / hauteur), avec une marge pour les étiquettes.
 */
export function overviewDistance(extent: { x: number; y: number; z: number }, aspect: number,
    fovDeg = CAMERA_FOV): number {
  const halfV = (fovDeg * Math.PI) / 360;
  const halfH = Math.atan(Math.tan(halfV) * Math.max(aspect, 0.1));
  const margin = 1.15;
  const byWidth = (extent.x * margin + 0.6) / Math.tan(halfH);
  const byHeight = (Math.max(extent.y, extent.z * Math.sin((OVERVIEW_ELEVATION_DEG * Math.PI) / 180)) * margin + 0.6)
    / Math.tan(halfV);
  // La profondeur avance vers la caméra : on recule d'autant.
  return Math.max(MIN_DISTANCE, Math.max(byWidth, byHeight) + extent.z * 0.35);
}
