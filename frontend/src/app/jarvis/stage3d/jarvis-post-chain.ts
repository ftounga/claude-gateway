import { QualityLevel } from './jarvis-render-capability';

/**
 * F-236 / SF-236-15 — **la chaîne de rendu par calque** (cadrage §5, §6.1). Fichier <b>pur</b> (sans `three`) : ce
 * que le moteur dessine après la scène, selon le niveau et l'appareil.
 *
 * <ul>
 *   <li><b>bloom</b> — SF-236-02 (Haut, Moyen) ;</li>
 *   <li><b>bokeh</b> — la profondeur de champ du calque L0 : étoiles et poussière hors du plan de netteté s'élargissent
 *   en disques doux (PC, Haut seulement). Faite <b>dans le shader des particules</b> et non par une passe à tampon
 *   de profondeur : les particules additives n'écrivent pas la profondeur, une passe floutait le cœur ;</li>
 *   <li><b>smaa</b> — anticrénelage après les passes (le MSAA du canevas ne vaut plus dès qu'on passe par des
 *   tampons) : PC, Haut et Moyen ;</li>
 *   <li><b>finish</b> — la passe de finition : vignette, grain très fin, aberration chromatique sur les bords
 *   (PC, Haut et Moyen ; jamais au téléphone, §12.2).</li>
 * </ul>
 * Le texte n'est jamais touché : étiquettes et panneaux sont du DOM.
 */
export interface PostChain {
  bloom: boolean;
  bokeh: boolean;
  smaa: boolean;
  finish: boolean;
}

export function postChain(level: QualityLevel, narrow: boolean): PostChain {
  const wide = !narrow;
  return {
    bloom: level !== 'low',
    bokeh: wide && level === 'high',
    smaa: wide && level !== 'low',
    finish: wide && level !== 'low',
  };
}

/** Réglages de la finition (valeurs bornées, lues par le shader). */
export const FINISH = {
  /** Assombrissement des coins (part). */
  vignette: 0.22,
  /** Amplitude du grain (0-1). */
  grain: 0.018,
  /** Décalage des canaux rouge / bleu sur les bords (part de l'écran, au carré de la distance au centre). */
  aberration: 0.006,
} as const;

/** Profondeur de champ du calque L0 : élargissement maximal d'un point flou (× sa taille). */
export const BOKEH_MAX_GROWTH = 1.6;
