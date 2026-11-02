import { QualityLevel } from '../jarvis-render-capability';

/**
 * F-236 / SF-236-20 — **le téléphone allégé** (cadrage §12.2) : la matrice « ce qui reste / ce qui disparaît »
 * écrite une fois, en configuration <b>pure</b>, lue par le moteur, le directeur et le cockpit. Chaque ligne de la
 * matrice est un champ ; les tests de configuration par profil vérifient chacune.
 */
export interface DeviceProfile {
  /** Cœur et états : restent ; particules réduites au téléphone (densité du niveau Moyen). */
  coreParticles: 'full' | 'reduced';
  /** Univers : sujets et chantiers du client courant ou cités seulement (D7). */
  subjects: 'all' | 'current-or-cited';
  /** Liaisons : le chemin du focus seulement ; le reste est absent (pas latent). */
  links: 'all' | 'focus-path';
  /** Panneaux de scène ancrés : 1 (2 pour S4, tickets). */
  panelLimit: (sceneId: string | null | undefined) => number;
  /** Entrée cinématique T-E1 (ms). */
  entryMs: number;
  /** Panneaux latéraux, journée, météo, personnes, chronologies, inspecteur : dans la scène, ou en volets. */
  views: 'panels' | 'volets';
  /** Tableaux flottants, mur de tickets, nuage du dépôt, horloge des veilles en 3D. */
  floatingBoards: boolean;
  /** Décor : nébuleuse, poussière, profondeur de champ, verre flouté, aberration, SMAA. */
  nebula: boolean;
  dust: boolean;
  bokeh: boolean;
  glassBlur: boolean;
  aberration: boolean;
  smaa: boolean;
  /** Sol polaire : complet, ou grille simple. */
  floor: 'polar' | 'grid';
  /** Essaim : sous-agents visibles au plus (+ « +n »). */
  swarm: number;
  /** Dérive automatique de la caméra (rotation lente de l'univers). */
  autoDrift: boolean;
  /** Vue cockpit sur écran externe (itération 4) : sans objet au téléphone. */
  cockpitWindow: boolean;
}

/** Entrée cinématique au téléphone (cadrage §12.2) ; PC 2,6 s (`ENTRY_FULL_MS`). */
export const PHONE_ENTRY_MS = 1200;

export const PC_PROFILE: DeviceProfile = {
  coreParticles: 'full', subjects: 'all', links: 'all', panelLimit: () => 8, entryMs: 2600, views: 'panels',
  floatingBoards: true, nebula: true, dust: true, bokeh: true, glassBlur: true, aberration: true, smaa: true,
  floor: 'polar', swarm: 4, autoDrift: true, cockpitWindow: true,
};

export const PHONE_PROFILE: DeviceProfile = {
  coreParticles: 'reduced', subjects: 'current-or-cited', links: 'focus-path',
  panelLimit: (sceneId) => (sceneId === 'S4' ? 2 : 1), entryMs: PHONE_ENTRY_MS, views: 'volets',
  floatingBoards: false, nebula: false, dust: false, bokeh: false, glassBlur: false, aberration: false, smaa: false,
  floor: 'grid', swarm: 2, autoDrift: false, cockpitWindow: false,
};

export function deviceProfile(narrow: boolean): DeviceProfile {
  return narrow ? PHONE_PROFILE : PC_PROFILE;
}

/** Ce que chaque niveau dessine (§6.1 / §6.3) ; le téléphone retire nébuleuse et poussière (§12.2). */
export interface LevelBudget {
  pixelRatio: number;
  starLayers: number;
  starsPerLayer: number;
  dust: number;
  floorRings: number;
  nebula: boolean;
}

const BUDGETS: Record<QualityLevel, LevelBudget> = {
  high: { pixelRatio: 2, starLayers: 3, starsPerLayer: 900, dust: 600, floorRings: 7, nebula: true },
  medium: { pixelRatio: 1.5, starLayers: 2, starsPerLayer: 500, dust: 250, floorRings: 5, nebula: true },
  low: { pixelRatio: 1, starLayers: 1, starsPerLayer: 300, dust: 0, floorRings: 3, nebula: false },
};

/** Le budget d'un niveau, allégé au téléphone : fond et étoiles simples (une profondeur), grille simple. */
export function levelBudget(level: QualityLevel, narrow: boolean): LevelBudget {
  const base = BUDGETS[level];
  if (!narrow) {
    return base;
  }
  return { ...base, starLayers: 1, dust: 0, nebula: false, floorRings: Math.min(base.floorRings, 3) };
}

/** Les vues ouvertes en volets plein écran au téléphone (une à la fois), depuis « ☰ Vues ». */
export type PhoneView = 'journee' | 'meteo' | 'personnes' | 'chronologies' | 'inspecteur';

export const PHONE_VIEWS: readonly { id: PhoneView; label: string }[] = [
  { id: 'journee', label: 'Aujourd\'hui' },
  { id: 'meteo', label: 'Météo' },
  { id: 'personnes', label: 'Personnes' },
  { id: 'chronologies', label: 'Chronologies' },
  { id: 'inspecteur', label: 'Inspecteur' },
];
