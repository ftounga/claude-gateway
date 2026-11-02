import { UniverseChantier } from '../../scenes/jarvis-universe.models';

/**
 * F-236 / SF-236-16 — **la météo des chantiers** (NF-2, cadrage §13). Fichier <b>pur</b> : un chantier ⇒ un ciel,
 * lu dans ce que l'univers sait déjà (phase, vivant, étape en cours, personnes attendues, avancement). Aucun appel.
 *
 * <p>Table de vérité (dans l'ordre) :</p>
 * <ol>
 *   <li>clos (phase `CLOS`, ou `done ≥ total > 0`) ⇒ <b>soleil</b> « terminé » ;</li>
 *   <li>attend quelqu'un ET rien ne bouge (ni vivant, ni étape en cours) ⇒ <b>orage</b> « bloqué » ;</li>
 *   <li>attend quelqu'un mais avance ⇒ <b>nuage</b> « attend X » ;</li>
 *   <li>vivant ou étape en cours ⇒ <b>soleil</b> « avance » ;</li>
 *   <li>sinon ⇒ <b>brume</b> « sans mouvement ».</li>
 * </ol>
 */
export type Weather = 'soleil' | 'nuage' | 'orage' | 'brume';

export interface ChantierWeather {
  weather: Weather;
  /** Le mot écrit à côté (la couleur et l'icône ne portent jamais seules l'information). */
  note: string;
}

export function weatherOf(ch: Pick<UniverseChantier, 'phase' | 'done' | 'total' | 'live' | 'step' | 'waitingOn'>):
    ChantierWeather {
  if (ch.phase === 'CLOS' || (ch.total > 0 && ch.done >= ch.total)) {
    return { weather: 'soleil', note: 'terminé' };
  }
  const moving = ch.live || ch.step !== null;
  if (ch.waitingOn.length > 0) {
    return moving ? { weather: 'nuage', note: `attend ${ch.waitingOn.join(', ')}` }
      : { weather: 'orage', note: `bloqué — attend ${ch.waitingOn.join(', ')}` };
  }
  return moving ? { weather: 'soleil', note: 'avance' } : { weather: 'brume', note: 'sans mouvement' };
}

/** Le nom du ciel, pour les lecteurs d'écran. */
export const WEATHER_WORD: Readonly<Record<Weather, string>> = {
  soleil: 'Soleil', nuage: 'Nuageux', orage: 'Orage', brume: 'Brume',
};

/** L'ordre de gravité (le plus grave d'abord) : la liste météo commence par les orages. */
export const WEATHER_RANK: Readonly<Record<Weather, number>> = { orage: 0, nuage: 1, brume: 2, soleil: 3 };

/**
 * L'icône du ciel (SVG en ligne, 16 × 16, `currentColor`) : texte statique, sans aucune donnée de l'utilisateur —
 * le moteur 3D la pose au-dessus du losange, le cockpit à côté du nom.
 */
export const WEATHER_ICON: Readonly<Record<Weather, string>> = {
  soleil: '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><circle cx="8" cy="8" r="3.2" '
    + 'fill="currentColor"/><path d="M8 1v2.2M8 12.8V15M1 8h2.2M12.8 8H15M3 3l1.5 1.5M11.5 11.5L13 13M3 13l1.5'
    + '-1.5M11.5 4.5L13 3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>',
  nuage: '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M4.5 12.5h7.2a2.6 2.6 0 0 0 '
    + '.3-5.2A3.6 3.6 0 0 0 5.1 6.4 3 3 0 0 0 4.5 12.5z" fill="none" stroke="currentColor" stroke-width="1.3"/>'
    + '</svg>',
  orage: '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M4.5 9.5h7.2a2.6 2.6 0 0 0 '
    + '.3-5.2A3.6 3.6 0 0 0 5.1 3.4 3 3 0 0 0 4.5 9.5z" fill="none" stroke="currentColor" stroke-width="1.3"/>'
    + '<path d="M8.6 9.5L7 12.2h2L7.6 15" fill="none" stroke="currentColor" stroke-width="1.3" '
    + 'stroke-linejoin="round"/></svg>',
  brume: '<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M2 5.5h12M3.5 8.5h9M2 11.5h12"'
    + ' stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>',
};
