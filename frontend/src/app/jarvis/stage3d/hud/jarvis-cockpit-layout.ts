/**
 * F-236 / SF-236-15 — **la mise en page du cockpit PC** (cadrage §5 calque L5, §12.1). Fichier <b>pur</b> : il dit
 * si l'écran est un cockpit (PC large), un téléphone ou rien (2D, sobre), la largeur des panneaux latéraux en verre,
 * leur repli (retenu par appareil) et le décalage du centre de l'univers quand un seul panneau est ouvert — panneaux
 * repliés, l'univers occupe toute la largeur.
 */

/** En dessous, pas de cockpit : l'écran reste celui de l'itération 2 (barre, sous-titres, compteur). */
export const COCKPIT_MIN_WIDTH = 1280;

/** Largeur des panneaux latéraux (px) et marge au bord. */
export const COCKPIT_PANEL = { left: 300, right: 320, gutter: 16 } as const;

/** La poignée d'un panneau replié reste visible (ouvrir d'un geste). */
export const COCKPIT_HANDLE = 28;

export type CockpitMode = 'cockpit' | 'phone' | 'none';

export interface CockpitContext {
  width: number;
  /** Téléphone (portrait étroit, relevé par la scène 3D). */
  narrow: boolean;
  /** La 3D est affichée (sa première image est dessinée). */
  shown3d: boolean;
  /** Mode sobre F-234 (D9). */
  sober: boolean;
}

/** Cockpit sur PC large en 3D ; téléphone en 3D ; rien sinon (2D, sobre : l'écran F-234 inchangé). */
export function cockpitMode(c: CockpitContext): CockpitMode {
  if (!c.shown3d || c.sober) {
    return 'none';
  }
  if (c.narrow) {
    return 'phone';
  }
  return c.width >= COCKPIT_MIN_WIDTH ? 'cockpit' : 'none';
}

/** Le repli de chaque panneau (`true` = replié). */
export interface CockpitFolds {
  left: boolean;
  right: boolean;
}

export const OPEN_FOLDS: CockpitFolds = { left: false, right: false };

/** La clé du repli, par appareil. */
export const COCKPIT_FOLDS_KEY = 'jarvis.cockpit.replis';

/** Lit le repli retenu ; stockage illisible ⇒ panneaux ouverts. */
export function readFolds(): CockpitFolds {
  try {
    const raw = localStorage.getItem(COCKPIT_FOLDS_KEY);
    return { left: raw?.includes('g') ?? false, right: raw?.includes('d') ?? false };
  } catch {
    return OPEN_FOLDS;
  }
}

export function writeFolds(folds: CockpitFolds): void {
  try {
    const raw = `${folds.left ? 'g' : ''}${folds.right ? 'd' : ''}`;
    if (raw) {
      localStorage.setItem(COCKPIT_FOLDS_KEY, raw);
    } else {
      localStorage.removeItem(COCKPIT_FOLDS_KEY);
    }
  } catch {
    // Stockage indisponible : le repli vaut pour cette ouverture seulement.
  }
}

/** Ce que les panneaux couvrent à gauche et à droite (px) ; 0 hors cockpit ou replié. */
export interface CockpitInsets {
  left: number;
  right: number;
}

export const NO_INSETS: CockpitInsets = { left: 0, right: 0 };

export function cockpitInsets(mode: CockpitMode, folds: CockpitFolds): CockpitInsets {
  if (mode !== 'cockpit') {
    return NO_INSETS;
  }
  return {
    left: folds.left ? 0 : COCKPIT_PANEL.left + COCKPIT_PANEL.gutter,
    right: folds.right ? 0 : COCKPIT_PANEL.right + COCKPIT_PANEL.gutter,
  };
}

/**
 * Le décalage horizontal (px) du centre de l'univers : le milieu de la zone libre entre les panneaux. Positif ⇒
 * vers la droite. Deux panneaux repliés ⇒ 0 (pleine largeur).
 */
export function viewShift(insets: CockpitInsets): number {
  return (insets.left - insets.right) / 2;
}

export function sameInsets(a: CockpitInsets, b: CockpitInsets): boolean {
  return a.left === b.left && a.right === b.right;
}
