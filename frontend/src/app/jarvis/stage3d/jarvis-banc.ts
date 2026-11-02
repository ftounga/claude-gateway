import { QualityLevel } from './jarvis-render-capability';

/**
 * F-236 / SF-236-01 — **le banc de captures** (cadrage T12). Fichier <b>pur</b>.
 *
 * <p>`?banc=<scène>&t=<ms>[&niveau=haut|moyen|bas]` fige l'horloge à `t` et graine le hasard : mêmes paramètres
 * ⇒ même image, d'où des captures de référence reproductibles. <b>Inopérant en production</b> : l'appelant passe
 * `isDevMode()` et le banc n'existe pas quand il vaut `false`.</p>
 */

export interface BancParams {
  /** La scène demandée (`coeur` au socle ; les SF suivantes en ajoutent). */
  scene: string;
  /** L'instant figé, en millisecondes depuis l'ouverture. */
  t: number;
  /** Niveau de qualité imposé (`niveau=haut|moyen|bas`), sinon celui de l'appareil. */
  level: QualityLevel | null;
}

const LEVELS: Readonly<Record<string, QualityLevel>> = { haut: 'high', moyen: 'medium', bas: 'low' };

function levelOf(raw: string | null): QualityLevel | null {
  return raw !== null && Object.hasOwn(LEVELS, raw) ? LEVELS[raw] : null;
}

/** Les scènes que le banc sait poser ; une scène inconnue retombe sur `coeur`. */
export const BANC_SCENES = ['coeur', 'ecoute', 'reflexion', 'parole', 'oui', 'coupure', 'veille', 'reveil',
  'cerveau-aller', 'cerveau', 'cerveau-retour', 'cerveau-annule', 'univers', 'univers-cite', 'univers-montrer',
  'univers-telephone', 'entree', 'entree-courte', 'focus-chantier', 'zoom', 'dezoom',
  // SF-236-08 — panneaux holographiques et S-GEN 3D.
  'scene-sgen', 'panneau-entree', 'panneau-sortie',
  // SF-236-09 — commandes, tickets, fichiers.
  'scene-commande', 'scene-tickets', 'scene-fichiers', 'scene-lecture', 'scene-recherche',
  // SF-236-10 — chantiers, étapes, attentes, « oui ».
  'scene-etape', 'scene-attente', 'scene-attente-levee', 'scene-oui', 'scene-question', 'scene-points',
  'scene-le-point',
  // SF-236-11 — À envoyer (S1 + T-P3) et « Montrer » (S20, 6 filtres).
  'scene-message', 'scene-envoi', 'montrer-clients', 'montrer-connectes', 'montrer-deconnectes',
  'montrer-chantiers-bloques', 'montrer-etapes-en-cours', 'montrer-a-envoyer',
  // SF-236-12 — web, création, vidéo, passation, mémoire, essaim, veille.
  'scene-web', 'scene-page', 'scene-montage', 'scene-video', 'scene-passation', 'scene-souvenir', 'scene-carte',
  'scene-essaim', 'scene-veille',
  // SF-236-13 — ambiance et alertes (A1-A5).
  'ambiance-poste-muet', 'ambiance-vivants', 'ambiance-cloche', 'ambiance-cout', 'ambiance-veille',
  // SF-236-15 — le cockpit PC : panneaux ouverts, repliés, filament liste ↔ nœud.
  'cockpit', 'cockpit-replie', 'cockpit-filament',
  // SF-236-16 — la journée (un chiffre touché : « Montrer » local) et la météo des chantiers.
  'cockpit-journee', 'cockpit-meteo',
  // SF-236-17 — chronologies (arc du tour, journée chargée, un tour touché).
  'cockpit-chronologies'] as const;

const SCENE_NAME = /^[a-z0-9-]{1,40}$/;

/** Lit le banc dans les paramètres d'URL ; `null` hors développement ou sans `banc`. */
export function readBanc(query: URLSearchParams | null, devMode: boolean): BancParams | null {
  if (!devMode || !query) {
    return null;
  }
  const raw = query.get('banc');
  if (raw === null) {
    return null;
  }
  const scene = SCENE_NAME.test(raw) && (BANC_SCENES as readonly string[]).includes(raw) ? raw : 'coeur';
  const t = Number(query.get('t') ?? '0');
  return {
    scene,
    t: Number.isFinite(t) && t >= 0 ? Math.min(t, 3_600_000) : 0,
    level: levelOf(query.get('niveau')),
  };
}

/** L'horloge du moteur : réelle, ou figée par le banc. */
export type EngineClock = () => number;

export function realClock(): EngineClock {
  const start = performance.now();
  return () => performance.now() - start;
}

export function frozenClock(t: number): EngineClock {
  return () => t;
}

/** PRNG déterministe (mulberry32) : même graine ⇒ même suite, au banc comme en usage. */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let x = a;
    x = Math.imul(x ^ (x >>> 15), x | 1);
    x ^= x + Math.imul(x ^ (x >>> 7), x | 61);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}
