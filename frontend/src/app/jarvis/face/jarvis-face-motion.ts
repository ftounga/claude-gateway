import { HeartMood } from '../stage3d/jarvis-heart';
import { MouthShape } from './jarvis-visemes';

/**
 * F-250 / SF-250-05 — **le visage vit** (cadrage §4.1) : clignements, regard, sourcils, ondes, éclairs, tête, selon
 * l'état de Jarvis. Fichier <b>pur</b> (ni `three`, ni Angular) : le visage 3D ne fait que poser ces valeurs en
 * uniformes.
 *
 * <p>Clignements et saccades sont des fonctions <b>déterministes du temps</b> (graine par fenêtre) : au banc, même
 * instant ⇒ même visage.</p>
 */

/** L'état courant du visage (lissé), réécrit à chaque image sans allocation. */
export interface FaceLook {
  /** Paupière posée par l'état (lissée), avant clignement. */
  lidBase: number;
  /** Paupière dessinée (clignement compris). */
  lid: number;
  gx: number;
  gy: number;
  brow: number;
  browR: number;
  eyeA: number;
  lipA: number;
  /** La bouche lissée (ressort ≈ 30 s⁻¹). */
  mouth: MouthShape;
  glow: number;
  think: number;
  auraA: number;
  pull: number;
  ringMode: number;
  ringLevel: number;
  tilt: number;
  roll: number;
  /** Teinte d'attente (« oui » attendu), 0-1. */
  wait: number;
  /** Présence des points (veille : 20 %). */
  points: number;
  /** Présence du fil de fer (veille : 0). */
  wire: number;
  scanY: number;
  auraPhase: number;
  ringPhase: number;
  dialA: number;
  dialB: number;
  swirl: number;
}

interface MoodTarget {
  lid: number;
  brow: number;
  browR: number;
  eyeA: number;
  lipA: number;
  think: number;
  auraA: number;
  pull: number;
  ringMode: number;
  tilt: number;
  roll: number;
  swirl: number;
  scanSpeed: number;
  wait: number;
  points: number;
  wire: number;
}

/** Les cibles par état (cadrage §4.1). */
export const FACE_MOODS: Readonly<Record<HeartMood, MoodTarget>> = {
  repos: { lid: 0.93, brow: 0, browR: 0, eyeA: 0.82, lipA: 0.7, think: 0, auraA: 0.5, pull: 0, ringMode: 0, tilt: 0,
    roll: 0, swirl: 0.12, scanSpeed: 0.16, wait: 0, points: 1, wire: 1 },
  ecoute: { lid: 1, brow: 0.014, browR: 0, eyeA: 1, lipA: 0.7, think: 0, auraA: 0.45, pull: 0.5, ringMode: 1,
    tilt: -0.05, roll: 0.05, swirl: 0.08, scanSpeed: 0.1, wait: 0, points: 1, wire: 1 },
  reflexion: { lid: 0.72, brow: 0, browR: 0.016, eyeA: 0.82, lipA: 0.7, think: 1, auraA: 0.8, pull: 0.15, ringMode: 0,
    tilt: 0.04, roll: 0, swirl: 0.85, scanSpeed: 0.45, wait: 0, points: 1, wire: 1 },
  parole: { lid: 0.93, brow: 0, browR: 0, eyeA: 0.82, lipA: 1, think: 0, auraA: 0.6, pull: 0, ringMode: 2, tilt: 0,
    roll: 0, swirl: 0.22, scanSpeed: 0.2, wait: 0, points: 1, wire: 1 },
  coupure: { lid: 1, brow: 0.006, browR: 0, eyeA: 1, lipA: 0.8, think: 0, auraA: 0.45, pull: 0.2, ringMode: 0, tilt: 0,
    roll: 0, swirl: 0.06, scanSpeed: 0.1, wait: 0, points: 1, wire: 1 },
  oui: { lid: 1, brow: 0.01, browR: 0, eyeA: 1, lipA: 0.8, think: 0, auraA: 0.5, pull: 0.1, ringMode: 1, tilt: -0.03,
    roll: 0, swirl: 0.1, scanSpeed: 0.12, wait: 1, points: 1, wire: 1 },
  veille: { lid: 0.35, brow: 0, browR: 0, eyeA: 0.5, lipA: 0.5, think: 0, auraA: 0.15, pull: 0, ringMode: 0, tilt: 0.05,
    roll: 0, swirl: 0.04, scanSpeed: 0.05, wait: 0, points: 0.2, wire: 0 },
};

/** Un état neuf, posé sur les cibles de `mood`. */
export function faceMoodTargets(mood: HeartMood): FaceLook {
  const m = FACE_MOODS[mood];
  return {
    lidBase: m.lid, lid: m.lid, gx: 0, gy: 0, brow: m.brow, browR: m.browR, eyeA: m.eyeA, lipA: m.lipA,
    mouth: { open: 0.02, wide: 0, round: 0, press: 0.12 }, glow: 0, think: m.think, auraA: m.auraA, pull: m.pull,
    ringMode: m.ringMode, ringLevel: 0, tilt: m.tilt, roll: m.roll, wait: m.wait, points: m.points, wire: m.wire,
    scanY: 1.2, auraPhase: 0, ringPhase: 0, dialA: 0, dialB: 0, swirl: m.swirl,
  };
}

const damp = (cur: number, target: number, rate: number, dt: number): number =>
  cur + (target - cur) * (1 - Math.exp(-rate * dt));

/** Hachage déterministe d'un entier → [0, 1). */
export function hash01(n: number): number {
  let x = Math.imul((n | 0) ^ 0x9e3779b9, 0x85ebca6b);
  x ^= x >>> 13;
  x = Math.imul(x, 0xc2b2ae35);
  x ^= x >>> 16;
  return (x >>> 0) / 4294967296;
}

/** Durée d'un clignement (s). */
export const BLINK_S = 0.17;
const BLINK_WINDOW_S = 4.2;

/**
 * Le clignement à l'instant `t` (s) : 0 ouvert → 1 fermé. Un clignement par fenêtre de 4,2 s, à un instant tiré de la
 * fenêtre (soit des intervalles de 2,4 à 6 s), jamais plus de 3 Hz.
 */
export function blinkAt(t: number): number {
  const k = Math.floor(t / BLINK_WINDOW_S);
  const start = k * BLINK_WINDOW_S + 0.4 + hash01(k) * (BLINK_WINDOW_S - 0.4 - BLINK_S);
  const p = (t - start) / BLINK_S;
  return p < 0 || p > 1 ? 0 : Math.sin(p * Math.PI);
}

/** La bouche à poser selon l'état : en parole, celle des lèvres (visèmes / niveau) ; sinon une pose de l'état. */
function mouthTarget(mood: HeartMood, input: MouthShape, out: MouthShape): MouthShape {
  if (mood === 'parole') {
    out.open = input.open;
    out.wide = input.wide;
    out.round = input.round;
    out.press = input.press;
  } else if (mood === 'ecoute') {
    out.open = 0.07; out.wide = 0.05; out.round = 0; out.press = 0;
  } else if (mood === 'reflexion') {
    out.open = 0; out.wide = -0.1; out.round = 0.1; out.press = 0.45;
  } else {
    out.open = 0.02; out.wide = 0; out.round = 0; out.press = mood === 'coupure' ? 0.5 : 0.12;
  }
  return out;
}

const scratch: MouthShape = { open: 0, wide: 0, round: 0, press: 0 };

/**
 * Avance le visage d'un pas : `sinceS` = temps depuis le changement d'état, `t` = horloge (s), `dt` = pas (s),
 * `level` = niveau du micro (écoute) ou de la voix, `mouth` = la bouche demandée (parole). Aucune allocation.
 */
export function faceLookAt(look: FaceLook, mood: HeartMood, sinceS: number, t: number, dt: number, level: number,
                           mouth: MouthShape, reduced: boolean): FaceLook {
  const m = FACE_MOODS[mood];
  const step = Math.max(0, Math.min(dt, 0.1));
  const target = mouthTarget(mood, mouth, scratch);
  const k = reduced ? 0.6 : 1;
  // Coupure : la bouche se ferme en ≈ 120 ms (ressort rapide).
  const rate = mood === 'coupure' ? 40 : 30;
  look.mouth.open = damp(look.mouth.open, target.open * k, rate, step);
  look.mouth.wide = damp(look.mouth.wide, target.wide * k, rate - 4, step);
  look.mouth.round = damp(look.mouth.round, target.round * k, rate - 4, step);
  look.mouth.press = damp(look.mouth.press, target.press, rate + 4, step);

  // Yeux : clignements (aucun en veille ni en mouvement réduit), regard.
  const blink = reduced || mood === 'veille' ? 0 : blinkAt(t);
  look.lidBase = damp(look.lidBase, m.lid, 8, step);
  look.lid = look.lidBase * (1 - 0.95 * blink);
  // Saccades : une cible par fenêtre de 1,6 s ; la réflexion regarde en haut à gauche, la coupure regarde le PO.
  const w = Math.floor(t / 1.6);
  const spread = mood === 'parole' ? 0.012 : 0.022;
  const gxT = reduced || mood === 'coupure' || mood === 'oui' ? 0
    : mood === 'reflexion' ? -0.018 : (hash01(w * 2 + 1) - 0.5) * spread;
  const gyT = reduced || mood === 'coupure' || mood === 'oui' ? 0
    : mood === 'reflexion' ? 0.012 : (hash01(w * 2 + 2) - 0.5) * 0.01;
  look.gx = damp(look.gx, gxT, 18, step);
  look.gy = damp(look.gy, gyT, 18, step);
  look.brow = damp(look.brow, mood === 'parole' ? 0.006 * look.mouth.open : m.brow, 6, step);
  look.browR = damp(look.browR, m.browR, 4, step);
  look.eyeA = damp(look.eyeA, m.eyeA, 4, step);
  look.lipA = damp(look.lipA, m.lipA, 5, step);

  // Ondes : amplitude = micro à l'écoute, ouverture de la bouche à la parole.
  const ringLevel = mood === 'ecoute' ? Math.min(1, 0.35 + level * 1.4)
    : mood === 'parole' ? Math.min(1, look.mouth.open * 1.4) : mood === 'oui' ? 0.35 : 0;
  look.ringLevel = damp(look.ringLevel, ringLevel, 14, step);
  look.glow = damp(look.glow, mood === 'parole' ? look.mouth.open : 0, 14, step);
  look.ringMode = m.ringMode;
  for (const key of ['think', 'auraA', 'pull', 'tilt', 'roll', 'wait', 'swirl'] as const) {
    look[key] = damp(look[key], m[key], 3, step);
  }
  look.points = damp(look.points, m.points, 2.5, step);
  look.wire = damp(look.wire, m.wire, 2.5, step);
  const mv = reduced ? 0 : 1;
  look.auraPhase += step * look.swirl * mv;
  look.ringPhase += step * (mood === 'parole' ? 0.9 : mood === 'oui' ? 0.3 : 0.55) * mv;
  look.scanY -= step * m.scanSpeed * 2.4 * mv;
  if (look.scanY < -1.1) {
    look.scanY = 1.25;
  }
  look.dialA += step * 0.05 * mv;
  look.dialB -= step * (0.09 + look.swirl * 0.2) * mv;
  void sinceS;
  return look;
}
