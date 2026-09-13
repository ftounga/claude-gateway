/**
 * **La densité de « Voir travailler »** (F-98 / SF-98-04) : aperçus (F-76) ou flux entiers (F-83).
 *
 * <p>Deux écrans pour un seul besoin sont devenus un écran et un sélecteur. Le choix est <b>retenu</b>
 * dans le navigateur : celui qui préfère la mosaïque la retrouve la fois suivante. La mémoire est
 * locale, comme les autres préférences d'écran (`cg_*`), et <b>tolérante aux pannes</b> — un
 * stockage refusé (navigation privée) ne casse rien, il ne retient simplement pas.</p>
 */

export type ForgeDensity = 'apercus' | 'flux';

/** Clé de stockage du choix. */
export const FORGE_DENSITY_STORAGE_KEY = 'cg_forge_densite';

/** Repli quand ni l'URL ni le stockage ne disent rien : les aperçus, qui n'ouvrent aucun flux. */
export const DEFAULT_FORGE_DENSITY: ForgeDensity = 'apercus';

/** La valeur si elle est une densité connue, sinon `null`. */
export function parseDensity(value: string | null | undefined): ForgeDensity | null {
  return value === 'apercus' || value === 'flux' ? value : null;
}

/** Le choix retenu, ou `null` s'il n'y en a pas — ou si le stockage est illisible ou refusé. */
export function readStoredDensity(): ForgeDensity | null {
  try {
    return parseDensity(localStorage.getItem(FORGE_DENSITY_STORAGE_KEY));
  } catch {
    return null;
  }
}

/** Retient le choix. Silencieux si le stockage est refusé : la page reste utilisable. */
export function storeDensity(density: ForgeDensity): void {
  try {
    localStorage.setItem(FORGE_DENSITY_STORAGE_KEY, density);
  } catch {
    // Navigation privée, quota, stockage bloqué : on ne retient pas, et c'est tout.
  }
}

/** La densité effective : celle de l'URL, sinon le choix retenu, sinon les aperçus. */
export function effectiveDensity(fromUrl: string | null | undefined): ForgeDensity {
  return parseDensity(fromUrl) ?? readStoredDensity() ?? DEFAULT_FORGE_DENSITY;
}
