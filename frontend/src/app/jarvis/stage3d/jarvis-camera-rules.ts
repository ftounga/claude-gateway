import { Node3dKind } from './jarvis-layout-3d';
import { CAMERA_SPRING_OMEGA, ease } from './jarvis-motion';

/**
 * F-236 — **les règles de caméra** (cadrage §9.3). Fichier <b>pur</b>.
 *
 * <p>SF-236-04 : la vue d'ensemble (sphère englobante ajustée au plus petit champ, portrait compris).
 * SF-236-05 : le directeur de caméra (vers le nœud cité, dézoom au changement de sujet ou après 10 s sans citation,
 * gel pendant l'écoute, ≤ 1 déplacement / 2,5 s, gestes prioritaires 8 s), le ressort amorti critique, et les
 * transitions d'écran (entrée T-E1 / T-E2, sortie T-S). Focale fixe 55°, aucun roulis.</p>
 */

/** Focale verticale fixe (§9.3). */
export const CAMERA_FOV = 55;
/** Élévation de la vue d'ensemble (T-Z2 : légère montée, 18°). */
export const OVERVIEW_ELEVATION_DEG = 18;
/** Distance minimale : le cœur et le grand cerveau tiennent toujours dans le champ. */
export const MIN_DISTANCE = 9;
/** Bornes du zoom manuel (pincer / molette). */
export const ZOOM_MIN = 2.5;
export const ZOOM_MAX = 60;
/** Tangage manuel borné (±35°). */
export const PITCH_LIMIT_DEG = 35;
/** Au plus un déplacement de caméra toutes les 2,5 s (anti mal des transports, §7.3 règle 8). */
export const MOVE_GAP_MS = 2500;
/** Sans nouvelle citation pendant 10 s ⇒ retour à la vue d'ensemble. */
export const IDLE_OVERVIEW_MS = 10_000;
/** Retour automatique après le dernier geste (T-Z4). */
export const MANUAL_HOLD_MS = 8000;
/** Sélection en espace écran : le nœud le plus proche à ≤ 28 px (cible ≥ 44 px garantie). */
export const PICK_RADIUS_PX = 28;

/** Distance de cadrage d'un nœud (§9.3) ; × 1,5 en portrait. */
export const NODE_DISTANCE: Record<Node3dKind, number> = { client: 7, subject: 5, chantier: 3.5, step: 2.5, person: 2.5 };

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

/** Où la caméra regarde et d'où (coordonnées de la disposition, cœur à l'origine). */
export interface CameraShot {
  kind: 'overview' | 'node';
  /** Le nœud cadré (`node`). */
  key: string | null;
  /** Point visé. */
  x: number;
  y: number;
  z: number;
  /** Lacet (rad, autour de l'axe vertical), tangage (rad), distance. */
  yaw: number;
  pitch: number;
  distance: number;
}

export interface CameraInput {
  /** Le nœud dont on parle (le premier cité), avec sa position. */
  focus: { key: string; kind: Node3dKind; x: number; y: number; z: number } | null;
  subjectId: string | null;
  /** Le PO parle : aucun déplacement ne démarre. */
  listening: boolean;
  /** Une autorisation S2 est ouverte : recul de 20 %. */
  waiting: boolean;
  overview: number;
  portrait: boolean;
}

export function overviewShot(distance: number, portrait: boolean): CameraShot {
  return { kind: 'overview', key: null, x: 0, y: 0, z: 0, yaw: 0,
    pitch: ((portrait ? 6 : OVERVIEW_ELEVATION_DEG) * Math.PI) / 180, distance };
}

/** Le plan qui cadre un nœud : la caméra se place au-delà du nœud, côté extérieur (le cœur n'est jamais traversé). */
export function nodeShot(focus: NonNullable<CameraInput['focus']>, portrait: boolean): CameraShot {
  const yaw = Math.atan2(focus.x, focus.z);
  return { kind: 'node', key: focus.key, x: focus.x, y: focus.y, z: focus.z, yaw, pitch: (12 * Math.PI) / 180,
    distance: NODE_DISTANCE[focus.kind] * (portrait ? 1.5 : 1) };
}

/**
 * **Le directeur de caméra** (§9.3). Pur : l'heure est passée à chaque appel. Il décide d'un plan (vue d'ensemble ou
 * nœud) et le tient : gel pendant l'écoute, au plus un nouveau plan toutes les 2,5 s, dézoom au changement de sujet
 * ou après 10 s sans citation. Le moteur rejoint le plan par ressort ; aucun roulis par construction.
 */
export class CameraDirector {
  private shot: CameraShot | null = null;
  private lastMove = -Infinity;
  private lastFocusAt = -Infinity;
  private subjectId: string | null | undefined = undefined;
  /** Plans pris depuis l'ouverture (mesure, tests). */
  moves = 0;

  decide(input: CameraInput, now: number): CameraShot {
    const wanted = this.wanted(input, now);
    if (!this.shot) {
      this.shot = wanted;
      this.subjectId = input.subjectId;
      return wanted;
    }
    const subjectChanged = input.subjectId !== this.subjectId;
    this.subjectId = input.subjectId;
    if (sameShot(wanted, this.shot)) {
      // Même plan : la distance d'ensemble suit l'univers (relecture) sans compter comme un déplacement.
      this.shot = { ...this.shot, distance: wanted.distance };
      return this.shot;
    }
    if (input.listening) {
      return this.shot;
    }
    if (now - this.lastMove < MOVE_GAP_MS && !subjectChanged) {
      return this.shot;
    }
    this.shot = wanted;
    this.lastMove = now;
    this.moves++;
    return wanted;
  }

  private wanted(input: CameraInput, now: number): CameraShot {
    if (input.focus) {
      this.lastFocusAt = now;
    }
    const recent = now - this.lastFocusAt < IDLE_OVERVIEW_MS;
    if (input.focus && !input.waiting) {
      return nodeShot(input.focus, input.portrait);
    }
    if (!input.focus && recent && this.shot?.kind === 'node' && input.subjectId === this.subjectId) {
      return this.shot;
    }
    return overviewShot(input.overview * (input.waiting ? 1.2 : 1), input.portrait);
  }
}

function sameShot(a: CameraShot, b: CameraShot): boolean {
  return a.kind === b.kind && a.key === b.key && Math.abs(a.pitch - b.pitch) < 1e-6;
}

/**
 * Ressort amorti **critique** (ω = 5,5 rad/s) : solution exacte, donc indépendante de la cadence ; parti au repos,
 * il ne dépasse jamais sa cible. Aucune allocation.
 */
export class Spring {
  value: number;
  velocity = 0;
  target: number;

  constructor(value: number, private readonly omega = CAMERA_SPRING_OMEGA) {
    this.value = value;
    this.target = value;
  }

  step(dtMs: number): number {
    const dt = Math.max(0, dtMs) / 1000;
    const x0 = this.value - this.target;
    const w = this.omega;
    const e = Math.exp(-w * dt);
    const k = this.velocity + w * x0;
    this.value = this.target + (x0 + k * dt) * e;
    this.velocity = (this.velocity - w * k * dt) * e;
    return this.value;
  }

  jump(value: number): void {
    this.value = value;
    this.target = value;
    this.velocity = 0;
  }
}

/** L'angle `to` ramené au plus près de `from` (le lacet prend toujours le chemin court). */
export function nearestAngle(from: number, to: number): number {
  let d = (to - from) % (Math.PI * 2);
  if (d > Math.PI) {
    d -= Math.PI * 2;
  } else if (d < -Math.PI) {
    d += Math.PI * 2;
  }
  return from + d;
}

/** Durées des transitions d'écran (§7.4). */
export const ENTRY_FULL_MS = 2600;
export const ENTRY_SHORT_MS = 900;
export const EXIT_MS = 500;
export const SKIP_FADE_MS = 300;

export type EntryKind = 'full' | 'short' | 'none';

/** L'état de l'entrée à `t` ms. */
export interface EntryFrame {
  /** Lumière du fond (0-1). */
  light: number;
  /** Échelle du cœur (0 = un point). */
  core: number;
  /** Facteur de distance de la caméra (la caméra recule depuis l'intérieur du cœur). */
  distance: number;
  /** Orbites tracées (0-1). */
  orbits: number;
  /** Clients allumés en cascade : avancement du i-ème client. */
  clientAt(index: number): number;
  /** Le HUD glisse depuis les bords (0-1). */
  hud: number;
  done: boolean;
}

/** T-E1 (2,6 s, première ouverture du jour) / T-E2 (0,9 s, ouvertures suivantes). */
export function entryFrame(kind: EntryKind, t: number): EntryFrame {
  if (kind === 'none' || (kind === 'full' && t >= ENTRY_FULL_MS) || (kind === 'short' && t >= ENTRY_SHORT_MS)) {
    return { light: 1, core: 1, distance: 1, orbits: 1, clientAt: () => 1, hud: 1, done: true };
  }
  if (kind === 'short') {
    const p = ease('sortie', t / ENTRY_SHORT_MS);
    return { light: 1, core: 0.96 + 0.04 * p, distance: 1 + 0.04 * (1 - p), orbits: p, clientAt: () => p,
      hud: ease('sortie', Math.min(1, t / 400)), done: false };
  }
  const span = (a: number, b: number, curve: 'sortie' | 'glisse' | 'rebond'): number => ease(curve, (t - a) / (b - a));
  return {
    light: span(0, 300, 'sortie'),
    core: span(200, 900, 'rebond'),
    distance: 0.08 + 0.92 * span(500, 1800, 'glisse'),
    orbits: span(900, 2000, 'sortie'),
    clientAt: (i) => span(1100 + 70 * i, 1400 + 70 * i, 'sortie'),
    hud: span(1900, ENTRY_FULL_MS, 'sortie'),
    done: false,
  };
}

/** T-S — la sortie à `t` ms : le HUD s'efface, l'univers se replie vers le cœur, le cœur se réduit à un point. */
export function exitFrame(t: number): { hud: number; universe: number; core: number } {
  return {
    hud: 1 - ease('entree', Math.min(1, t / 200)),
    universe: 1 - ease('entree', Math.min(1, t / 350)),
    core: t < 350 ? 1 : 1 - ease('entree', Math.min(1, (t - 350) / 150)),
  };
}

/** L'entrée du jour : la première ouverture du jour joue T-E1, les suivantes T-E2. */
export const ENTRY_KEY = 'cg.jarvis.entry';

export function entryKindToday(today: string): EntryKind {
  try {
    const seen = localStorage.getItem(ENTRY_KEY);
    localStorage.setItem(ENTRY_KEY, today);
    return seen === today ? 'short' : 'full';
  } catch {
    return 'short';
  }
}

/** Le nœud le plus proche d'un toucher, en pixels d'écran, à ≤ {@link PICK_RADIUS_PX} ; `null` sinon. */
export function pickNearest<T extends { sx: number; sy: number; visible: boolean }>(points: readonly T[], x: number,
    y: number, radius = PICK_RADIUS_PX): T | null {
  let best: T | null = null;
  let bestD = radius * radius;
  for (const p of points) {
    if (!p.visible) {
      continue;
    }
    const d = (p.sx - x) ** 2 + (p.sy - y) ** 2;
    if (d <= bestD) {
      bestD = d;
      best = p;
    }
  }
  return best;
}
