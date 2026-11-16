import { CAMERA_SPRING_OMEGA, HOLO_ALPHA, HoloRole, MOTION_DURATIONS, MotionCurve, ease } from '../jarvis-motion';
import {
  ArchLayout, ICON_SIZE, NODE_FLOAT, STEP_COMPACT, STEP_EXPLODED, STEP_EXPLODED_PHONE, pathPoints,
} from './architecture-layout-3d';

/**
 * F-238 / SF-238-09 — **le directeur de la scène d'architecture** (cadrage §6) : fichier <b>pur</b>, aucun `three`.
 *
 * <p>(graphe posé, état de navigation) ⇒ {@link ArchitecturePlan} déclaratif (caméra, palier et rôle de couleur de
 * chaque plateau, service et liaison, flux animés, étiquettes) ; puis, à chaque image, (plan, phase, instant) ⇒
 * {@link ArchFrame} écrit <b>en place</b> (0 allocation) : altitudes, échelles, tracés, éclats des transitions T-A1
 * (entrée, 2,2 s) et T-A2 (sortie, 0,6 s). Le moteur dessine et interpole, comme F-236 ; même plan + même instant ⇒
 * même image (banc `?banc=architecture&t=`).</p>
 */

/** Champ vertical fixe de la scène (focale fixe : la vue de dessus est le 2D cadré exactement, T-A9). */
export const ARCH_FOV = 40;
/** Vue d'ensemble (T-A9) : élévation 32°, azimut 35°. */
export const ENSEMBLE_ELEVATION = (32 * Math.PI) / 180;
export const ENSEMBLE_AZIMUTH = (35 * Math.PI) / 180;
/** SF-238-11 — vue d'ensemble en portrait (téléphone) : plus haute, le schéma tient en grand. */
export const PORTRAIT_ELEVATION = (55 * Math.PI) / 180;
/** Le 2D cadré : contenu `contain`, centré, marge de 5 % de chaque côté. */
export const SVG_FIT = 0.9;
/** Vue d'ensemble : marge minimale au bord du cadre (le schéma occupe le centre, en grand). */
export const ENSEMBLE_MARGIN = 0.05;
/** SF-238-15 — marge latérale minimale (px) : la demi-largeur d'une étiquette de service. */
export const LABEL_MARGIN_PX = 24;
/** Au plus trois éléments allumés à la fois (règle F-234 D2). */
export const MAX_LIT = 3;
/** Étiquettes (cadrage §8.2, §14). */
export const LABEL_ALL_UNDER = 24;
export const LABEL_NEIGHBOURS_UNDER = 40;
export const PHONE_LABELS = 16;
/** SF-238-11 — téléphone : libellés de zones au plus, et l'ordre des zones qui structurent le schéma. */
export const PHONE_ZONE_LABELS = 7;
export const PHONE_ZONE_ORDER: readonly string[] = ['az', 'subnet_public', 'subnet_private', 'vpc', 'asg',
  'security_group', 'region', 'account', 'onprem', 'generic', 'cloud'];

export type ArchView = 'ensemble' | 'dessus' | 'face';
/** SF-238-10 — vue de face : élévation 10° (le bas de l'orbite bornée), azimut 0. */
export const FACE_ELEVATION = (10 * Math.PI) / 180;
/** SF-238-10 — orbite bornée (cadrage §12) : élévation de 10° à 90°. */
export const MIN_ELEVATION = (10 * Math.PI) / 180;
export const MAX_ELEVATION = Math.PI / 2;
/** SF-238-10 (§8.2) — au-delà de 40 services, les AZ jumelles se replient en une seule « AZ × n ». */
export const TWINS_OVER = 40;
export type ArchPhaseKind = 'entree' | 'repos' | 'sortie';

export interface ArchState {
  view: ArchView;
  exploded: boolean;
  /** Le service ou la zone au centre de l'attention (id du graphe), sinon `null`. */
  focus: string | null;
  /** Ce dont Jarvis parle (ids de services, zones ou liaisons) : au plus {@link MAX_LIT}. */
  lit: readonly string[];
  /** SF-238-12 — les liaisons des flux allumés par la narration (T-A7). */
  flow?: readonly string[];
  phone: boolean;
  /** SF-238-10 — zones repliées en capsule (ids). */
  folded?: readonly string[];
  /** SF-238-10 — zoom du réducteur commun (×1 à {@link ARCH_MAX_ZOOM}) : la caméra se rapproche d'autant. */
  zoom?: number;
  /**
   * SF-238-15 — décalage de la cible dans le plan (unités, x et z), posé par le zoom vers le point visé ; borné à
   * l'emprise des services ({@link applyZoomPan}).
   */
  pan?: { x: number; z: number };
  /** SF-238-10 — décalage d'orbite (radians), posé au toucher ou par « tourne ». */
  orbit?: { az: number; el: number };
}

export const DEFAULT_ARCH_STATE: ArchState = { view: 'ensemble', exploded: false, focus: null, lit: [], phone: false };

export interface Viewport {
  width: number;
  height: number;
}

export interface ArchCamera {
  target: [number, number, number];
  /** Radians : azimut autour de l'axe vertical (0 = vu depuis +z), élévation (π/2 = zénith). */
  azimuth: number;
  elevation: number;
  distance: number;
  /** Champ vertical, degrés. */
  fov: number;
}

export interface ArchitecturePlan {
  state: ArchState;
  camera: ArchCamera;
  /** SF-238-15 — la caméra de la même vue à ×1 sans décalage (référence du zoom vers le point visé). */
  base: ArchCamera;
  /** Δ entre deux niveaux, et facteur d'altitude (0 en vue de dessus : plateaux couchés, D-09-1). */
  step: number;
  lift: number;
  /** Paliers {@link HOLO_ALPHA} : fond et liseré des plateaux, halo des services, filaments. */
  plateFill: Float32Array;
  plateLine: Float32Array;
  plateRole: HoloRole[];
  nodeAlpha: Float32Array;
  nodeRole: HoloRole[];
  edgeAlpha: Float32Array;
  edgeRole: HoloRole[];
  /** SF-238-12 — 1 si la liaison est dans un flux allumé (T-A7), 1 si le service est allumé (T-A8). */
  edgeInFlow: Uint8Array;
  nodeLit: Uint8Array;
  /** Vitesse des particules par liaison (× {@link FLOW_SPEED} ; 0 = pas de flux). */
  edgeFlow: Float32Array;
  nodeLabel: boolean[];
  plateLabel: boolean[];
  /** Le héros (règle F-236 §7.3-6) : ce dont Jarvis parle, sinon le focus. */
  hero: string | null;
  /** SF-238-10 — 1 : service rentré dans une capsule (zone repliée ou AZ jumelle fusionnée). */
  nodeFold: Float32Array;
  /** SF-238-10 — la capsule qui l'a rentré (indice de plateau), `-1` sinon. */
  nodeHome: Int16Array;
  /** SF-238-10 — 1 : plateau dans une zone repliée (ni fond, ni liseré, ni libellé). */
  plateHidden: Float32Array;
  /** SF-238-10 — 1 : plateau replié en capsule ; texte de sa capsule (« 7 services ») ; badge (« × 3 »). */
  plateCapsule: Float32Array;
  capsuleText: string[];
  plateBadge: string[];
}

/** SF-238-10 — un déplacement en cours (caméra, altitudes, capsules) : repart de la valeur affichée (règle 2). */
export interface ArchMove {
  at: number;
  ms: number;
  curve: MotionCurve | 'ressort';
  fromCamera: ArchCamera;
  fromAlt: Float32Array;
  fromFold: Float32Array;
  fromHidden: Float32Array;
  fromCapsule: Float32Array;
}

/** L'image à l'instant : tampons écrits en place par {@link ArchitectureStageDirector.frame}. */
export interface ArchFrame {
  /** 0 = le panneau 2D debout face à la caméra, 1 = couché en plateau racine (T-A1, 0-400 ms). */
  lay: number;
  /** Altitude de chaque plateau. */
  plateAlt: Float32Array;
  plateFill: Float32Array;
  plateLine: Float32Array;
  /** Hauteur de vol des services au-dessus de leur plateau. */
  float: number;
  nodeScale: Float32Array;
  nodeAlpha: Float32Array;
  /** Part tracée de chaque filament (0-1, de `from` vers `to`). */
  edgeTrace: Float32Array;
  edgeAlpha: Float32Array;
  /** SF-238-12 — T-A7 : part balayée du chemin d'un flux allumé (0-1, segment par segment). */
  edgeSweep: Float32Array;
  /** Parcours des particules (en secondes de flux) : continu, accéléré ×2 pendant 2 s après T-A7, sans saut. */
  edgeTravel: Float32Array;
  particles: number;
  labels: number;
  /** SF-238-10 — repli à l'instant : part rentrée de chaque service (0-1), sa position, plateaux masqués, capsules. */
  nodeFold: Float32Array;
  nodeX: Float32Array;
  nodeZ: Float32Array;
  plateHidden: Float32Array;
  plateCapsule: Float32Array;
}

/** T-A1 : jalons (ms) du tableau §10.1. */
export const ENTRY = {
  layEnd: 400,
  riseStart: 300, riseEnd: 1100, riseStagger: 60,
  poseStart: 700, poseEnd: 1500, poseStagger: 25,
  traceStart: 1100, traceEnd: 1900, traceStagger: 20,
  sparkStart: 1600,
} as const;

/** T-A2 : jalons (ms). */
export const EXIT = {
  sparkEnd: 150, poseEnd: 400, traceStart: 100, traceEnd: 400, riseStart: 200, riseEnd: 500, layStart: 400,
} as const;

function clamp01(v: number): number {
  return v <= 0 ? 0 : v >= 1 ? 1 : v;
}

/** Avancement d'une fenêtre [a, b] à l'instant t. */
function span(t: number, a: number, b: number): number {
  return b <= a ? (t >= b ? 1 : 0) : clamp01((t - a) / (b - a));
}

/** Écart et durée d'une cascade de `n` éléments dans une fenêtre, l'écart nominal borné pour tenir dedans. */
function cascade(n: number, window: number, nominal: number): { gap: number; dur: number } {
  const gap = n > 1 ? Math.min(nominal, (window * 0.6) / (n - 1)) : 0;
  return { gap, dur: window - gap * Math.max(0, n - 1) };
}

/** Le 2D cadré : échelle (px écran par px du graphe) et décalage. */
export function svgFit(bounds: { w: number; h: number }, viewport: Viewport): { scale: number; dx: number; dy: number } {
  const scale = Math.min((viewport.width * SVG_FIT) / bounds.w, (viewport.height * SVG_FIT) / bounds.h);
  return { scale, dx: (viewport.width - bounds.w * scale) / 2, dy: (viewport.height - bounds.h * scale) / 2 };
}

/** Un point du graphe (px) dans le 2D cadré affiché (px écran). */
export function svgToScreen(bounds: { w: number; h: number }, viewport: Viewport, x: number, y: number):
    { x: number; y: number } {
  const fit = svgFit(bounds, viewport);
  return { x: fit.dx + x * fit.scale, y: fit.dy + y * fit.scale };
}

/** Repère de la caméra : position, droite, haut, avant (le moteur pose `three` avec exactement ce repère). */
export function cameraBasis(camera: ArchCamera): { position: number[]; right: number[]; up: number[]; forward: number[] } {
  const ce = Math.cos(camera.elevation);
  const se = Math.sin(camera.elevation);
  const sa = Math.sin(camera.azimuth);
  const ca = Math.cos(camera.azimuth);
  const dir = [ce * sa, se, ce * ca];
  const position = [camera.target[0] + dir[0] * camera.distance, camera.target[1] + dir[1] * camera.distance,
    camera.target[2] + dir[2] * camera.distance];
  const forward = [-dir[0], -dir[1], -dir[2]];
  const right = [ca, 0, -sa];
  // haut = droite × avant (défini même au zénith, où « haut » du monde est dégénéré).
  const up = [right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2],
    right[0] * forward[1] - right[1] * forward[0]];
  return { position, right, up, forward };
}

/** Projection perspective pure d'un point du monde à l'écran (px), comme la caméra du moteur. */
export function projectToScreen(camera: ArchCamera, viewport: Viewport, x: number, y: number, z: number):
    { x: number; y: number } {
  const b = cameraBasis(camera);
  const v = [x - b.position[0], y - b.position[1], z - b.position[2]];
  const xc = v[0] * b.right[0] + v[1] * b.right[1] + v[2] * b.right[2];
  const yc = v[0] * b.up[0] + v[1] * b.up[1] + v[2] * b.up[2];
  const zc = v[0] * b.forward[0] + v[1] * b.forward[1] + v[2] * b.forward[2];
  const tan = Math.tan((camera.fov * Math.PI) / 360);
  const aspect = viewport.width / viewport.height;
  const nx = xc / (zc * tan * aspect);
  const ny = yc / (zc * tan);
  return { x: ((nx + 1) / 2) * viewport.width, y: ((1 - ny) / 2) * viewport.height };
}

/** Le Δ d'un état. */
export function stepOf(state: ArchState): number {
  return state.exploded ? (state.phone ? STEP_EXPLODED_PHONE : STEP_EXPLODED) : STEP_COMPACT;
}

/** SF-238-10 — l'emprise cadrée (plan, unités) : tout le schéma, ou le focus et ses voisins (T-A6). */
export interface ArchExtent {
  x0: number;
  x1: number;
  z0: number;
  z1: number;
}

/** La caméra d'une vue (T-A9), cadrée sur l'emprise (tout le schéma, ou le focus : T-A6), zoom et orbite compris. */
export function cameraFor(layout: ArchLayout, state: ArchState, viewport: Viewport, extent: ArchExtent | null = null):
    ArchCamera {
  return applyZoomPan(layout, state, baseCameraFor(layout, state, viewport, extent), viewport);
}

/** SF-238-15 — la caméra d'une vue à ×1, sans décalage : le cadrage de repos (orbite comprise). */
export function baseCameraFor(layout: ArchLayout, state: ArchState, viewport: Viewport,
    extent: ArchExtent | null = null): ArchCamera {
  if (state.view === 'dessus' && !extent) {
    const tan = Math.tan((ARCH_FOV * Math.PI) / 360);
    // Zénith : la largeur visible au sol est exactement celle du 2D cadré.
    const fit = svgFit(layout.graph.bounds, viewport);
    const distance = (viewport.height * 0.01) / (2 * fit.scale * tan);
    return { target: [0, 0, 0], azimuth: 0, elevation: Math.PI / 2, distance, fov: ARCH_FOV };
  }
  const height = (state.view === 'dessus' ? 0 : layout.maxLevel * stepOf(state)) + NODE_FLOAT;
  const orbit = state.view === 'dessus' ? { az: 0, el: 0 } : state.orbit ?? { az: 0, el: 0 };
  // SF-238-11 — en portrait, la vue d'ensemble se lève (55°) : la profondeur du schéma occupe la hauteur de l'écran.
  const baseEl = state.view === 'dessus' ? MAX_ELEVATION : state.view === 'face' ? FACE_ELEVATION
    : viewport.height > viewport.width ? PORTRAIT_ELEVATION : ENSEMBLE_ELEVATION;
  const baseAz = state.view === 'ensemble' ? ENSEMBLE_AZIMUTH : 0;
  const e = extent ?? { x0: -layout.width / 2, x1: layout.width / 2, z0: -layout.depth / 2, z1: layout.depth / 2 };
  const start = (az: number): ArchCamera => ({
    target: [(e.x0 + e.x1) / 2, height / 2, (e.z0 + e.z1) / 2], azimuth: az + orbit.az,
    elevation: Math.min(MAX_ELEVATION, Math.max(MIN_ELEVATION, baseEl + orbit.el)), distance: 1, fov: ARCH_FOV,
  });
  // SF-238-11 — écran en portrait (téléphone) : le schéma se tourne d'un quart de tour si sa longueur tient mieux
  // dans la hauteur de l'écran ; on garde l'orientation qui le rend le plus grand (la plus petite distance).
  const portrait = state.view !== 'dessus' && viewport.height > viewport.width;
  // De face (azimut 0 ou 90°), un schéma ne s'étale pas en diagonale : 30 à 40 % plus grand dans la largeur.
  const bases = portrait && state.view === 'ensemble' ? [0, Math.PI / 2] : portrait ? [baseAz, baseAz + Math.PI / 2]
    : [baseAz];
  // SF-238-15 — tout le schéma : on cadre les vrais volumes (plateaux à leur altitude, services), pas la boîte
  // « emprise × hauteur max » dont le haut est vide au-dessus des plateaux bas (le schéma remplit le cadre).
  const points = extent ? boxCorners(e, height)
    : volumePoints(layout, state.view === 'dessus' ? 0 : stepOf(state));
  let best: ArchCamera | null = null;
  for (const az of bases) {
    const fitted = fitCamera(start(az), points, viewport);
    if (!best || fitted.distance < best.distance * 0.98) {
      best = fitted;
    }
  }
  // Éclaté : le volume plus haut est recadré (la caméra recule d'elle-même, T-A3).
  return extent ? best as ArchCamera : onServicePlane(layout, state, best as ArchCamera);
}

/**
 * SF-238-15 — même image, cible reposée **sur la ligne de visée** à l'altitude moyenne des services (le recentrage
 * du cadrage peut la pousser sous le sol) : la caméra ne bouge pas, mais le zoom rapproche ensuite vers le plan des
 * services au lieu d'un point enfoui, et la borne « hors de la boîte » se mesure depuis l'intérieur du schéma.
 */
function onServicePlane(layout: ArchLayout, state: ArchState, camera: ArchCamera): ArchCamera {
  const step = state.view === 'dessus' ? 0 : stepOf(state);
  if (layout.nodes.length === 0) {
    return camera;
  }
  const y = layout.nodes.reduce((sum, n) => sum + (n.plate >= 0 ? layout.plates[n.plate].level * step : 0), 0)
    / layout.nodes.length + NODE_FLOAT;
  const dir = viewDir(camera);
  if (dir[1] < 0.05) {
    return camera;
  }
  const u = (y - camera.target[1]) / dir[1];
  if (u >= camera.distance * 0.9) {
    return camera;
  }
  return { ...camera, distance: camera.distance - u,
    target: [camera.target[0] + dir[0] * u, y, camera.target[2] + dir[2] * u] };
}

/** Les huit coins d'une emprise × hauteur. */
function boxCorners(e: ArchExtent, height: number): number[][] {
  return [[e.x0, 0, e.z0], [e.x1, 0, e.z0], [e.x0, 0, e.z1], [e.x1, 0, e.z1], [e.x0, height, e.z0],
    [e.x1, height, e.z0], [e.x0, height, e.z1], [e.x1, height, e.z1]];
}

/**
 * SF-238-15 — les points qui portent le dessin : coins de chaque plateau à son altitude et au sol (ses parois),
 * icônes des services (pleine taille) et place de leur étiquette (devant l'icône).
 */
export function volumePoints(layout: ArchLayout, step: number): number[][] {
  const out: number[][] = [];
  for (const p of layout.plates) {
    for (const y of [0, p.level * step]) {
      out.push([p.x0, y, p.z0], [p.x0 + p.w, y, p.z0], [p.x0, y, p.z0 + p.d], [p.x0 + p.w, y, p.z0 + p.d]);
    }
  }
  const half = ICON_SIZE / 2;
  for (const n of layout.nodes) {
    const y = (n.plate >= 0 ? layout.plates[n.plate].level * step : 0) + NODE_FLOAT;
    out.push([n.x - half, y - half, n.z], [n.x + half, y - half, n.z], [n.x - half, y + half, n.z],
      [n.x + half, y + half, n.z], [n.x, y, n.z + half + 0.3]);
  }
  return out;
}

/** Le volume (points) au centre et en grand pour une orientation donnée. */
function fitCamera(initial: ArchCamera, corners: number[][], viewport: Viewport): ArchCamera {
  let camera = initial;
  const box = (c: ArchCamera): { x0: number; x1: number; y0: number; y1: number } => {
    let x0 = Infinity;
    let x1 = -Infinity;
    let y0 = Infinity;
    let y1 = -Infinity;
    for (const [x, y, z] of corners) {
      const p = projectToScreen(c, viewport, x, y, z);
      x0 = Math.min(x0, p.x);
      x1 = Math.max(x1, p.x);
      y0 = Math.min(y0, p.y);
      y1 = Math.max(y1, p.y);
    }
    return { x0, x1, y0, y1 };
  };
  // SF-238-15 — au moins 24 px de côté : l'étiquette d'un service au bord (« Utilisateurs ») reste entière.
  const mx = Math.max(viewport.width * ENSEMBLE_MARGIN, LABEL_MARGIN_PX);
  const my = viewport.height * ENSEMBLE_MARGIN;
  const tan = Math.tan((ARCH_FOV * Math.PI) / 360);
  const aspect = viewport.width / viewport.height;
  // Le volume au centre et en grand : on recentre le point visé sur l'emprise projetée, puis on cherche la plus
  // petite distance où les huit coins tiennent dans le cadre (bissection) ; trois passes suffisent.
  for (let pass = 0; pass < 3; pass++) {
    let lo = 0.5;
    let hi = 400;
    for (let i = 0; i < 40; i++) {
      const mid = (lo + hi) / 2;
      const b = box({ ...camera, distance: mid });
      if (b.x0 >= mx && b.x1 <= viewport.width - mx && b.y0 >= my && b.y1 <= viewport.height - my) {
        hi = mid;
      } else {
        lo = mid;
      }
    }
    camera = { ...camera, distance: hi };
    const b = box(camera);
    const basis = cameraBasis(camera);
    const sx = (((b.x0 + b.x1) / 2 - viewport.width / 2) / (viewport.width / 2)) * tan * aspect * camera.distance;
    const sy = -(((b.y0 + b.y1) / 2 - viewport.height / 2) / (viewport.height / 2)) * tan * camera.distance;
    camera = {
      ...camera,
      target: [camera.target[0] + basis.right[0] * sx + basis.up[0] * sy,
        camera.target[1] + basis.right[1] * sx + basis.up[1] * sy,
        camera.target[2] + basis.right[2] * sx + basis.up[2] * sy],
    };
  }
  return camera;
}

/** SF-238-15 — zoom de l'architecture : ×1 (vue d'ensemble) à ×4 (au-delà, on ne lit plus le schéma). */
export const ARCH_MAX_ZOOM = 4;
/** SF-238-15 — marge entre la caméra et la boîte de la scène (une icône) : jamais dans un plateau. */
const CAMERA_CLEARANCE = ICON_SIZE;

/** La direction de la cible vers la caméra (unitaire). */
function viewDir(camera: ArchCamera): number[] {
  const ce = Math.cos(camera.elevation);
  return [ce * Math.sin(camera.azimuth), Math.sin(camera.elevation), ce * Math.cos(camera.azimuth)];
}

/**
 * SF-238-15 — la distance à laquelle le rayon parti de `target` dans la direction `dir` ressort de la boîte (méthode
 * des tranches), que la cible soit dedans ou devant ; 0 si le rayon ne la traverse pas.
 */
export function exitDistance(box: { min: number[]; max: number[] }, target: number[], dir: number[]): number {
  let enter = -Infinity;
  let exit = Infinity;
  for (let k = 0; k < 3; k++) {
    if (Math.abs(dir[k]) < 1e-9) {
      if (target[k] < box.min[k] || target[k] > box.max[k]) {
        return 0;
      }
      continue;
    }
    const a = (box.min[k] - target[k]) / dir[k];
    const b = (box.max[k] - target[k]) / dir[k];
    enter = Math.max(enter, Math.min(a, b));
    exit = Math.min(exit, Math.max(a, b));
  }
  return enter <= exit && exit > 0 ? exit : 0;
}

/** SF-238-15 — l'emprise des services (plan), où la cible décalée reste : un service au moins reste au cadre. */
function nodeSpan(layout: ArchLayout): ArchExtent {
  if (layout.nodes.length === 0) {
    return { x0: -layout.width / 2, x1: layout.width / 2, z0: -layout.depth / 2, z1: layout.depth / 2 };
  }
  const xs = layout.nodes.map((n) => n.x);
  const zs = layout.nodes.map((n) => n.z);
  return { x0: Math.min(...xs), x1: Math.max(...xs), z0: Math.min(...zs), z1: Math.max(...zs) };
}

function clampTo(v: number, a: number, b: number): number {
  return Math.min(Math.max(v, Math.min(a, b)), Math.max(a, b));
}

/** SF-238-15 — la cible décalée de `pan`, bornée à l'emprise des services (la cible de repos reste permise). */
function pannedTarget(layout: ArchLayout, base: ArchCamera, pan: { x: number; z: number }): [number, number, number] {
  const span = nodeSpan(layout);
  const x = clampTo(base.target[0] + pan.x, Math.min(span.x0, base.target[0]), Math.max(span.x1, base.target[0]));
  const z = clampTo(base.target[2] + pan.z, Math.min(span.z0, base.target[2]), Math.max(span.z1, base.target[2]));
  return [x, base.target[1], z];
}

/** SF-238-15 — les volumes que la caméra ne traverse jamais : chaque plateau, du sol à son altitude. */
export function sceneVolumes(layout: ArchLayout, step: number): { min: number[]; max: number[] }[] {
  return layout.plates.map((p) => ({ min: [p.x0, 0, p.z0], max: [p.x0 + p.w, p.level * step, p.z0 + p.d] }));
}

/** SF-238-15 — une icône n'occupe jamais plus de cette part de la plus petite dimension du cadre. */
export const ICON_MAX_SHARE = 0.4;

/**
 * SF-238-15 — le recul minimal devant une icône : à cette distance elle occupe au plus {@link ICON_MAX_SHARE} de la
 * plus petite dimension du cadre (en portrait, la largeur).
 */
export function iconClearance(camera: ArchCamera, viewport: Viewport): number {
  const tan = Math.tan((camera.fov * Math.PI) / 360);
  const aspect = viewport.width / Math.max(1, viewport.height);
  return ICON_SIZE / (2 * tan * Math.min(1, aspect) * ICON_MAX_SHARE);
}

/**
 * SF-238-15 — la distance la plus courte permise pour cette cible : en rapprochant depuis le recul de repos, la
 * caméra s'arrête avant d'entrer dans le premier plateau rencontré (marge d'une icône) et reste à distance de chaque
 * icône, sur l'axe comme en bord de cadre ({@link iconClearance}) : jamais dans le schéma, jamais une icône géante.
 */
function minDistance(layout: ArchLayout, state: ArchState, camera: ArchCamera, viewport: Viewport): number {
  const dir = viewDir(camera);
  const t = camera.target;
  const step = state.view === 'dessus' ? 0 : stepOf(state);
  let floor = 0;
  for (const v of sceneVolumes(layout, step)) {
    const exit = exitDistance(v, t, dir);
    if (exit > 0) {
      floor = Math.max(floor, exit + CAMERA_CLEARANCE);
    }
  }
  // Sphère de rayon r autour de chaque icône : |t + dir·D − c| ≥ r ⇒ D au-delà de la plus grande racine.
  const r = iconClearance(camera, viewport);
  for (const n of layout.nodes) {
    const c = [n.x, (n.plate >= 0 ? layout.plates[n.plate].level * step : 0) + NODE_FLOAT, n.z];
    const o = [t[0] - c[0], t[1] - c[1], t[2] - c[2]];
    const b = o[0] * dir[0] + o[1] * dir[1] + o[2] * dir[2];
    const disc = b * b - (o[0] * o[0] + o[1] * o[1] + o[2] * o[2] - r * r);
    if (disc > 0) {
      floor = Math.max(floor, -b + Math.sqrt(disc));
    }
  }
  return Math.max(floor, CAMERA_CLEARANCE);
}

/** SF-238-15 — combien de services sont devant la caméra et dans le cadre (marge `inset`, fraction du cadre). */
export function servicesInView(layout: ArchLayout, state: ArchState, camera: ArchCamera, viewport: Viewport,
    inset = 0.04): number {
  const b = cameraBasis(camera);
  const step = state.view === 'dessus' ? 0 : stepOf(state);
  const mx = viewport.width * inset;
  const my = viewport.height * inset;
  let n = 0;
  for (const node of layout.nodes) {
    const y = (node.plate >= 0 ? layout.plates[node.plate].level * step : 0) + NODE_FLOAT;
    const ahead = (node.x - b.position[0]) * b.forward[0] + (y - b.position[1]) * b.forward[1]
      + (node.z - b.position[2]) * b.forward[2];
    if (ahead <= 0.05) {
      continue;
    }
    const p = projectToScreen(camera, viewport, node.x, y, node.z);
    if (p.x >= mx && p.x <= viewport.width - mx && p.y >= my && p.y <= viewport.height - my) {
      n++;
    }
  }
  return n;
}

/**
 * SF-238-15 — zoom et décalage sur la caméra de repos : la cible glisse (bornée à l'emprise des services), la
 * distance raccourcit d'autant, sans jamais entrer dans la boîte de la scène (ni dépasser le recul de repos). Si
 * aucun service ne reste au cadre (zoom au milieu d'un vide), la cible se pose sur le service le plus proche.
 */
export function applyZoomPan(layout: ArchLayout, state: ArchState, base: ArchCamera, viewport: Viewport): ArchCamera {
  const zoom = Math.min(ARCH_MAX_ZOOM, Math.max(1, state.zoom ?? 1));
  const pan = state.pan ?? { x: 0, z: 0 };
  if (zoom === 1 && pan.x === 0 && pan.z === 0) {
    return base;
  }
  const place = (target: [number, number, number]): ArchCamera => {
    const camera: ArchCamera = { ...base, target };
    const floor = Math.min(base.distance, minDistance(layout, state, camera, viewport));
    return { ...camera, distance: Math.max(base.distance / zoom, floor) };
  };
  const camera = place(pannedTarget(layout, base, pan));
  if (layout.nodes.length === 0 || servicesInView(layout, state, camera, viewport) > 0) {
    return camera;
  }
  const step = state.view === 'dessus' ? 0 : stepOf(state);
  let best: [number, number, number] = camera.target;
  let gap = Infinity;
  for (const n of layout.nodes) {
    const y = (n.plate >= 0 ? layout.plates[n.plate].level * step : 0) + NODE_FLOAT;
    const d = Math.hypot(n.x - camera.target[0], y - camera.target[1], n.z - camera.target[2]);
    if (d < gap) {
      gap = d;
      best = [n.x, y, n.z];
    }
  }
  return place(best);
}

/** Le point du plan horizontal `y = planeY` sous un point de l'écran (px), ou `null` (rayon parallèle ou derrière). */
export function groundAt(camera: ArchCamera, viewport: Viewport, sx: number, sy: number, planeY: number):
    { x: number; z: number } | null {
  const b = cameraBasis(camera);
  const tan = Math.tan((camera.fov * Math.PI) / 360);
  const aspect = viewport.width / viewport.height;
  const nx = ((sx / viewport.width) * 2 - 1) * tan * aspect;
  const ny = (1 - (sy / viewport.height) * 2) * tan;
  const ray = [0, 1, 2].map((k) => b.forward[k] + b.right[k] * nx + b.up[k] * ny);
  if (Math.abs(ray[1]) < 1e-6) {
    return null;
  }
  const t = (planeY - b.position[1]) / ray[1];
  return t > 0 ? { x: b.position[0] + ray[0] * t, z: b.position[2] + ray[2] * t } : null;
}

/**
 * SF-238-15 — **zoomer vers le point visé** (molette au survol, milieu du pincement, centre pour « + / − ») : pur.
 * En rapprochant, le point visé reste sous le doigt (la cible glisse vers lui à proportion) ; en éloignant, le
 * décalage revient vers 0 à proportion (×1 = la vue d'ensemble exacte). Le zoom rendu est celui que la caméra peut
 * réellement atteindre (borne de distance), pour qu'un dézoom réponde tout de suite.
 */
export function zoomToward(layout: ArchLayout, state: ArchState, base: ArchCamera, viewport: Viewport,
    point: { x: number; y: number } | null, factor: number): { zoom: number; pan: { x: number; z: number } } {
  const zoom = Math.min(ARCH_MAX_ZOOM, Math.max(1, state.zoom ?? 1));
  const pan = state.pan ?? { x: 0, z: 0 };
  let next = Math.min(ARCH_MAX_ZOOM, Math.max(1, zoom * factor));
  if (next <= 1.0001) {
    return { zoom: 1, pan: { x: 0, z: 0 } };
  }
  let nextPan: { x: number; z: number };
  if (next < zoom) {
    const k = (next - 1) / (zoom - 1);
    nextPan = { x: pan.x * k, z: pan.z * k };
  } else {
    const current = applyZoomPan(layout, state, base, viewport);
    const at = point ? groundAt(current, viewport, point.x, point.y, current.target[1]) : null;
    const span = nodeSpan(layout);
    const a = at ? { x: clampTo(at.x, span.x0, span.x1), z: clampTo(at.z, span.z0, span.z1) }
      : { x: current.target[0], z: current.target[2] };
    const k = zoom / next;
    const tx = a.x + (current.target[0] - a.x) * k;
    const tz = a.z + (current.target[2] - a.z) * k;
    nextPan = { x: tx - base.target[0], z: tz - base.target[2] };
  }
  // Le décalage et le zoom rendus sont ceux que la caméra atteint vraiment (cible bornée, distance plancher, service
  // au cadre) : pas de dérive au bord, et un dézoom répond tout de suite.
  const shown = applyZoomPan(layout, { ...state, zoom: next, pan: nextPan }, base, viewport);
  next = Math.min(next, base.distance / shown.distance);
  if (next <= 1.0001) {
    return { zoom: 1, pan: { x: 0, z: 0 } };
  }
  return { zoom: next, pan: { x: shown.target[0] - base.target[0], z: shown.target[2] - base.target[2] } };
}

/** SF-238-10 — la caméra entre deux plans (cible et distance linéaires, azimut par le plus court chemin). */
export function blendCamera(a: ArchCamera, b: ArchCamera, p: number, out?: ArchCamera): ArchCamera {
  let da = b.azimuth - a.azimuth;
  da = Math.atan2(Math.sin(da), Math.cos(da));
  const o = out ?? { target: [0, 0, 0], azimuth: 0, elevation: 0, distance: 1, fov: ARCH_FOV };
  o.target[0] = a.target[0] + (b.target[0] - a.target[0]) * p;
  o.target[1] = a.target[1] + (b.target[1] - a.target[1]) * p;
  o.target[2] = a.target[2] + (b.target[2] - a.target[2]) * p;
  o.azimuth = a.azimuth + da * p;
  o.elevation = a.elevation + (b.elevation - a.elevation) * p;
  o.distance = a.distance * Math.pow(b.distance / Math.max(1e-6, a.distance), p);
  o.fov = a.fov + (b.fov - a.fov) * p;
  return o;
}

/** SF-238-10 — le ressort de caméra (amorti critique, sans dépassement) ramené à [0, 1] sur la durée. */
export function springProgress(u: number, durationMs: number): number {
  if (u <= 0) {
    return 0;
  }
  if (u >= 1) {
    return 1;
  }
  const k = (CAMERA_SPRING_OMEGA * durationMs) / 1000;
  const f = (x: number): number => 1 - (1 + k * x) * Math.exp(-k * x);
  return f(u) / f(1);
}

/** SF-238-10 — l'avancement d'un déplacement à l'instant `now` (0-1). */
export function moveProgress(move: ArchMove | null, now: number): number {
  if (!move || move.ms <= 0) {
    return 1;
  }
  const u = span(now, move.at, move.at + move.ms);
  return move.curve === 'ressort' ? springProgress(u, move.ms) : ease(move.curve, u);
}

/** SF-238-10 — rayon de toucher d'un service (px écran) : au-delà, on touche la zone. */
export const PICK_RADIUS_PX = 40;

/**
 * SF-238-10 — l'élément sous un point de l'écran, à l'image affichée : le service le plus proche (≤ 40 px du centre
 * de son icône, capsules exclues), sinon la zone la plus profonde dont l'emprise projetée contient le point.
 */
export function pickAt(layout: ArchLayout, frame: ArchFrame, camera: ArchCamera, viewport: Viewport, x: number,
    y: number): { kind: 'node' | 'plate'; id: string } | null {
  let best: { id: string; d: number } | null = null;
  for (const n of layout.nodes) {
    if (frame.nodeFold[n.index] > 0.5) {
      continue;
    }
    const alt = n.plate < 0 ? 0 : frame.plateAlt[n.plate];
    const q = projectToScreen(camera, viewport, frame.nodeX[n.index], alt + frame.float, frame.nodeZ[n.index]);
    const d = Math.hypot(q.x - x, q.y - y);
    if (d <= PICK_RADIUS_PX && (!best || d < best.d)) {
      best = { id: n.id, d };
    }
  }
  if (best) {
    return { kind: 'node', id: best.id };
  }
  let plate: { id: string; level: number } | null = null;
  for (const p of layout.plates) {
    if (frame.plateHidden[p.index] > 0.5) {
      continue;
    }
    const alt = frame.plateAlt[p.index];
    const corners = [[p.x0, p.z0], [p.x0 + p.w, p.z0], [p.x0 + p.w, p.z0 + p.d], [p.x0, p.z0 + p.d]]
      .map(([cx, cz]) => projectToScreen(camera, viewport, cx, alt, cz));
    // Le point dans le quadrilatère projeté (convexe) : même côté de chaque arête.
    let sign = 0;
    let inside = true;
    for (let i = 0; i < 4 && inside; i++) {
      const a = corners[i];
      const b = corners[(i + 1) % 4];
      const cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
      if (cross !== 0) {
        if (sign === 0) {
          sign = Math.sign(cross);
        } else if (Math.sign(cross) !== sign) {
          inside = false;
        }
      }
    }
    if (inside && (!plate || p.level > plate.level)) {
      plate = { id: p.id, level: p.level };
    }
  }
  return plate ? { kind: 'plate', id: plate.id } : null;
}

/** SF-238-10 — durées des transitions de navigation (cadrage §10.1). */
export const NAV_MOVES = {
  view: 1600, focus: 1400, explode: 1200, fold: 700, unfold: 800, zoom: 320, orbit: 160,
} as const;

/** SF-238-14 — anti mal des transports (F-236 §7.3) : au plus un grand déplacement de caméra par 2,5 s. */
export const CAMERA_PACE_MS = 2500;
/** En deçà, un déplacement est un geste direct (orbite au doigt) : jamais ralenti. */
export const MAJOR_MOVE_MS = 600;

/**
 * SF-238-14 — la durée d'un déplacement qui suit de près le précédent : il ne finit jamais avant 2,5 s après le début
 * du précédent (la caméra ralentit au lieu d'enchaîner deux élans). Pur.
 */
export function paceMove(lastMajorAt: number | null, at: number, ms: number): number {
  if (ms < MAJOR_MOVE_MS || lastMajorAt === null) {
    return ms;
  }
  const since = at - lastMajorAt;
  return since >= CAMERA_PACE_MS ? ms : Math.max(ms, CAMERA_PACE_MS - since);
}

/** SF-238-10 — la transition d'un changement d'état (T-A3 → T-A6, T-A9) ; 0 ms : seule la lumière change. */
export function moveFor(prev: ArchState, next: ArchState): { ms: number; curve: MotionCurve | 'ressort' } {
  const folded = (s: ArchState): string => [...(s.folded ?? [])].sort().join();
  if (prev.view !== next.view) {
    return { ms: NAV_MOVES.view, curve: 'ressort' };
  }
  if (prev.focus !== next.focus) {
    return { ms: NAV_MOVES.focus, curve: 'ressort' };
  }
  if (prev.exploded !== next.exploded) {
    return { ms: NAV_MOVES.explode, curve: 'glisse' };
  }
  if (folded(prev) !== folded(next)) {
    return (next.folded ?? []).length >= (prev.folded ?? []).length ? { ms: NAV_MOVES.fold, curve: 'entree' }
      : { ms: NAV_MOVES.unfold, curve: 'sortie' };
  }
  const pp = prev.pan ?? { x: 0, z: 0 };
  const np = next.pan ?? { x: 0, z: 0 };
  if ((prev.zoom ?? 1) !== (next.zoom ?? 1) || pp.x !== np.x || pp.z !== np.z) {
    return { ms: NAV_MOVES.zoom, curve: 'glisse' };
  }
  const po = prev.orbit ?? { az: 0, el: 0 };
  const no = next.orbit ?? { az: 0, el: 0 };
  if (po.az !== no.az || po.el !== no.el) {
    return { ms: NAV_MOVES.orbit, curve: 'glisse' };
  }
  return { ms: 0, curve: 'glisse' };
}

/** SF-238-12 — la phrase en cours : ce qui était allumé avant, et quand la phrase a commencé (ms). */
export interface ArchTransition {
  from: ArchitecturePlan;
  at: number;
}

/** T-A7 / T-A8 (cadrage §10.1). */
export const NARRATION = {
  lightMs: 350, fadeMs: 900, segmentMs: 150, sweepMaxMs: 900, boostMs: 2000, pulse: 0.15,
} as const;

export class ArchitectureStageDirector {
  /** T-A7 : durée du tracé de chaque liaison (150 ms par segment, borné à 0,9 s). */
  private readonly sweepMs: Float32Array;
  /** Cascades de T-A1, calculées une fois (le chemin d'image n'alloue rien). */
  private readonly rise: { gap: number; dur: number };
  private readonly pose: { gap: number; dur: number };
  private readonly trace: { gap: number; dur: number };

  constructor(readonly layout: ArchLayout) {
    this.rise = cascade(layout.plates.length, ENTRY.riseEnd - ENTRY.riseStart, ENTRY.riseStagger);
    this.pose = cascade(layout.nodes.length, ENTRY.poseEnd - ENTRY.poseStart, ENTRY.poseStagger);
    this.trace = cascade(layout.edges.length, ENTRY.traceEnd - ENTRY.traceStart, ENTRY.traceStagger);
    this.sweepMs = Float32Array.from(layout.edges.map((e) => Math.min(NARRATION.sweepMaxMs,
      NARRATION.segmentMs * (pathPoints(e) - 1))));
    this.twins = this.computeTwins();
  }

  /**
   * SF-238-10 (§8.2) — les AZ jumelles : sœurs de même gabarit (mêmes types de services, par nature de sous-réseau).
   * Rend, pour chaque jumelle fusionnée, l'indice de celle qui reste (la première) ; vide sous {@link TWINS_OVER}.
   */
  readonly twins: ReadonlyMap<number, number>;

  private computeTwins(): Map<number, number> {
    const { plates, nodes } = this.layout;
    const out = new Map<number, number>();
    if (nodes.length <= TWINS_OVER) {
      return out;
    }
    const inside = (p: number, root: number): boolean => {
      for (let q = p, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
        if (q === root) {
          return true;
        }
      }
      return false;
    };
    const signature = (az: number): string => {
      const parts: string[] = [];
      for (const n of nodes) {
        if (n.plate >= 0 && inside(n.plate, az)) {
          parts.push(`${n.plate === az ? 'az' : plates[n.plate].kind}:${n.type}`);
        }
      }
      return parts.sort().join('|');
    };
    const byParent = new Map<number, number[]>();
    plates.filter((p) => p.kind === 'az').forEach((p) => byParent.set(p.parent, [...(byParent.get(p.parent) ?? []), p.index]));
    for (const siblings of byParent.values()) {
      const seen = new Map<string, number>();
      for (const az of siblings) {
        const sig = signature(az);
        if (!sig) {
          continue;
        }
        const first = seen.get(sig);
        if (first === undefined) {
          seen.set(sig, az);
        } else {
          out.set(az, first);
        }
      }
    }
    return out;
  }

  /** Le plan d'un état : alloué au changement d'état seulement, jamais par image. */
  plan(state: ArchState, viewport: Viewport): ArchitecturePlan {
    const { plates, nodes, edges } = this.layout;
    // Au plus 3 GROUPES allumés : la narration (SF-238-12) l'assure ; un type (« les instances ») en compte un seul.
    const lit = new Set(state.lit);
    const flowSet = new Set(state.flow ?? []);
    const focus = state.focus;

    // Le focus : un service (lui, ses voisins, leurs plateaux) ou une zone (elle, ses descendants, leurs services).
    let inFocus: ((kind: 'plate' | 'node' | 'edge', i: number) => boolean) | null = null;
    if (focus !== null) {
      const node = this.layout.nodeById.get(focus);
      const plate = this.layout.plateById.get(focus);
      const keepNodes = new Set<number>();
      const keepPlates = new Set<number>();
      const keepEdges = new Set<number>();
      const climb = (p: number): void => {
        for (let q = p, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
          keepPlates.add(q);
        }
      };
      if (node !== undefined) {
        keepNodes.add(node);
        for (const e of edges) {
          if (e.from === node || e.to === node) {
            keepEdges.add(e.index);
            keepNodes.add(e.from);
            keepNodes.add(e.to);
          }
        }
        keepNodes.forEach((n) => climb(nodes[n].plate));
      } else if (plate !== undefined) {
        const under = (p: number): boolean => {
          for (let q = p, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
            if (q === plate) {
              return true;
            }
          }
          return false;
        };
        plates.forEach((p) => under(p.index) && keepPlates.add(p.index));
        climb(plate);
        nodes.forEach((n) => under(n.plate) && keepNodes.add(n.index));
        edges.forEach((e) => (keepNodes.has(e.from) || keepNodes.has(e.to)) && keepEdges.add(e.index));
      }
      if (node !== undefined || plate !== undefined) {
        inFocus = (kind, i) => (kind === 'plate' ? keepPlates : kind === 'node' ? keepNodes : keepEdges).has(i);
      }
    }
    const rest = (kind: 'plate' | 'node' | 'edge', i: number): boolean => !inFocus || inFocus(kind, i);

    const plateFill = new Float32Array(plates.length);
    const plateLine = new Float32Array(plates.length);
    const plateRole: HoloRole[] = [];
    plates.forEach((p, i) => {
      const on = lit.has(p.id);
      // Le fond ne s'éclaire (contexte) que pour la zone dont on parle ou qui a le focus : les plateaux emboîtés
      // s'additionnent, un fond éclairé sur toute la chaîne brûlerait l'image.
      plateFill[i] = on || focus === p.id ? HOLO_ALPHA.context : HOLO_ALPHA.latent;
      plateLine[i] = on ? HOLO_ALPHA.lit : rest('plate', i) ? HOLO_ALPHA.active : HOLO_ALPHA.context;
      plateRole.push(on ? 'core' : 'normal');
    });
    const nodeAlpha = new Float32Array(nodes.length);
    const nodeRole: HoloRole[] = [];
    nodes.forEach((n, i) => {
      const on = lit.has(n.id);
      nodeAlpha[i] = on ? HOLO_ALPHA.lit : focus === n.id ? HOLO_ALPHA.panel
        : rest('node', i) ? HOLO_ALPHA.active : HOLO_ALPHA.context;
      nodeRole.push(on ? 'core' : 'normal');
    });
    const edgeAlpha = new Float32Array(edges.length);
    const edgeRole: HoloRole[] = [];
    const edgeFlow = new Float32Array(edges.length);
    const edgeInFlow = new Uint8Array(edges.length);
    edges.forEach((e, i) => {
      const on = lit.has(e.id) || flowSet.has(e.id);
      edgeInFlow[i] = on ? 1 : 0;
      edgeAlpha[i] = on ? HOLO_ALPHA.lit : rest('edge', i) ? HOLO_ALPHA.active : HOLO_ALPHA.context;
      edgeRole.push(on ? 'core' : 'normal');
      // Hors focus, plus de particules ; l'accélération ×2 d'un flux allumé est calculée par image (T-A7).
      edgeFlow[i] = on || rest('edge', i) ? 1 : 0;
    });
    const nodeLit = Uint8Array.from(nodes.map((n) => (lit.has(n.id) ? 1 : 0)));

    // Étiquettes (§8.2, §14) : toutes jusqu'à 24 services ; au-delà, le focus et ses voisins ; > 40, le focus seul.
    const count = nodes.length;
    const budget = state.phone ? PHONE_LABELS : Infinity;
    const neighbours = (i: number): boolean => focus !== null && (nodes[i].id === focus || (inFocus !== null
      && inFocus('node', i)));
    const nodeLabel = nodes.map((n, i) => {
      if (lit.has(n.id) || n.id === focus) {
        return true;
      }
      if (count <= LABEL_ALL_UNDER && count <= budget) {
        return true;
      }
      return count <= LABEL_NEIGHBOURS_UNDER && neighbours(i);
    });
    let plateLabel: boolean[];
    if (state.phone) {
      // SF-238-11 — téléphone : les zones qui structurent le schéma d'abord (AZ, sous-réseaux, VPC…, au plus
      // {@link PHONE_ZONE_LABELS}), puis les services dans l'ordre du flux, 16 étiquettes en tout (cadrage §14).
      const rank = (k: string): number => PHONE_ZONE_ORDER.indexOf(k) < 0 ? PHONE_ZONE_ORDER.length
        : PHONE_ZONE_ORDER.indexOf(k);
      const zones = plates.map((p) => p.index).sort((a, b) => rank(plates[a].kind) - rank(plates[b].kind) || a - b)
        .slice(0, PHONE_ZONE_LABELS);
      const zoneSet = new Set(zones);
      plateLabel = plates.map((p) => lit.has(p.id) || p.id === focus || zoneSet.has(p.index));
      let room = Math.max(0, PHONE_LABELS - plateLabel.filter(Boolean).length);
      const order = nodes.map((n) => n.index).sort((a, b) => nodes[a].flowRank - nodes[b].flowRank || a - b);
      const forced = new Set(nodes.filter((n) => lit.has(n.id) || n.id === focus).map((n) => n.index));
      room -= forced.size;
      for (const i of order) {
        const wanted = forced.has(i) || (nodeLabel[i] && room > 0);
        if (nodeLabel[i] && !forced.has(i) && wanted) {
          room--;
        }
        nodeLabel[i] = wanted;
      }
    } else {
      plateLabel = plates.map(() => true);
    }

    // SF-238-10 (§8.3) — replis : une zone repliée rentre ses services en capsule au centre de son plateau (le
    // plateau garde son emprise : rien ne saute) ; ses plateaux intérieurs s'effacent. Au-delà de 40 services, les
    // AZ jumelles fusionnent dans la première (§8.2), sauf celle qui porte le focus.
    const ancestorOf = (p: number, root: number): boolean => {
      for (let q = p, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
        if (q === root) {
          return true;
        }
      }
      return false;
    };
    const capsules = new Map<number, string>();
    for (const id of state.folded ?? []) {
      const p = this.layout.plateById.get(id);
      if (p !== undefined) {
        capsules.set(p, '');
      }
    }
    const focusPlate = focus === null ? -1 : this.layout.plateById.get(focus)
      ?? (this.layout.nodeById.has(focus) ? nodes[this.layout.nodeById.get(focus) as number].plate : -1);
    const badges = new Map<number, number>();
    this.twins.forEach((first, twin) => {
      if (focusPlate >= 0 && ancestorOf(focusPlate, twin)) {
        return;
      }
      capsules.set(twin, `= ${plates[first].label}`);
      badges.set(first, (badges.get(first) ?? 1) + 1);
    });
    // Un plateau replié dans un autre replié : seule la capsule extérieure compte.
    const outer = [...capsules.keys()].filter((p) => ![...capsules.keys()].some((q) => q !== p && ancestorOf(p, q)));
    const nodeFold = new Float32Array(nodes.length);
    const nodeHome = new Int16Array(nodes.length).fill(-1);
    const plateHidden = new Float32Array(plates.length);
    const plateCapsule = new Float32Array(plates.length);
    const capsuleText = plates.map(() => '');
    for (const c of outer) {
      plateCapsule[c] = 1;
      let count = 0;
      nodes.forEach((n) => {
        if (n.plate >= 0 && ancestorOf(n.plate, c)) {
          nodeFold[n.index] = 1;
          nodeHome[n.index] = c;
          nodeLabel[n.index] = false;
          count++;
        }
      });
      plates.forEach((p) => {
        if (p.index !== c && ancestorOf(p.index, c)) {
          plateHidden[p.index] = 1;
          plateLabel[p.index] = false;
        }
      });
      capsuleText[c] = capsules.get(c) || `⌬ ${count} service${count > 1 ? 's' : ''}`;
    }
    // Une liaison vers une capsule s'éteint (latente, sans particules) : elle ne pointe plus vers un service visible.
    edges.forEach((e, i) => {
      if (nodeFold[e.from] || nodeFold[e.to]) {
        edgeAlpha[i] = Math.min(edgeAlpha[i], HOLO_ALPHA.latent);
        edgeFlow[i] = 0;
      }
    });
    const plateBadge = plates.map((p) => (badges.has(p.index) ? `× ${badges.get(p.index)}` : ''));

    // T-A6 : le focus cadre ses voisins (service) ou sa zone ; sinon tout le schéma.
    let extent: ArchExtent | null = null;
    if (inFocus !== null) {
      const node = this.layout.nodeById.get(focus as string);
      const plate = this.layout.plateById.get(focus as string);
      if (plate !== undefined) {
        const q = plates[plate];
        extent = { x0: q.x0, x1: q.x0 + q.w, z0: q.z0, z1: q.z0 + q.d };
      } else if (node !== undefined) {
        const keep = nodes.filter((n) => inFocus?.('node', n.index));
        const pad = ICON_SIZE;
        extent = { x0: Math.min(...keep.map((n) => n.x)) - pad, x1: Math.max(...keep.map((n) => n.x)) + pad,
          z0: Math.min(...keep.map((n) => n.z)) - pad, z1: Math.max(...keep.map((n) => n.z)) + pad };
      }
    }

    return {
      state,
      nodeFold, nodeHome, plateHidden, plateCapsule, capsuleText, plateBadge,
      ...this.cameras(state, viewport, extent),
      step: stepOf(state),
      lift: state.view === 'dessus' ? 0 : 1,
      plateFill, plateLine, plateRole, nodeAlpha, nodeRole, edgeAlpha, edgeRole, edgeFlow, nodeLabel, plateLabel,
      edgeInFlow, nodeLit,
      hero: state.lit[0] ?? state.flow?.[0] ?? focus,
    };
  }

  /** SF-238-15 — la caméra de repos de la vue (×1, sans décalage) et la caméra zoomée / décalée. */
  private cameras(state: ArchState, viewport: Viewport, extent: ArchExtent | null): { camera: ArchCamera;
      base: ArchCamera } {
    const base = baseCameraFor(this.layout, state, viewport, extent);
    return { base, camera: applyZoomPan(this.layout, state, base, viewport) };
  }

  /** Les tampons d'image à la taille du graphe (une fois, à la pose de la scène). */
  newFrame(): ArchFrame {
    const { plates, nodes, edges } = this.layout;
    return {
      lay: 1, plateAlt: new Float32Array(plates.length), plateFill: new Float32Array(plates.length),
      plateLine: new Float32Array(plates.length), float: NODE_FLOAT, nodeScale: new Float32Array(nodes.length),
      nodeAlpha: new Float32Array(nodes.length), edgeTrace: new Float32Array(edges.length),
      edgeAlpha: new Float32Array(edges.length), edgeSweep: new Float32Array(edges.length),
      edgeTravel: new Float32Array(edges.length), particles: 1, labels: 1,
      nodeFold: new Float32Array(nodes.length), nodeX: Float32Array.from(nodes.map((n) => n.x)),
      nodeZ: Float32Array.from(nodes.map((n) => n.z)), plateHidden: new Float32Array(plates.length),
      plateCapsule: new Float32Array(plates.length),
    };
  }

  /**
   * SF-238-10 — ce qui est affiché maintenant, en point de départ d'une nouvelle transition (règle 2 : une
   * transition interrompue repart de la valeur courante). Alloué au changement d'état seulement.
   */
  snapshot(plan: ArchitecturePlan, frame: ArchFrame): ArchitecturePlan {
    return {
      ...plan, plateFill: frame.plateFill.slice(), plateLine: frame.plateLine.slice(),
      nodeAlpha: frame.nodeAlpha.slice(), edgeAlpha: frame.edgeAlpha.slice(),
    };
  }

  /** SF-238-10 — le déplacement qui part de l'image affichée vers le plan suivant. */
  startMove(frame: ArchFrame, camera: ArchCamera, at: number, ms: number, curve: MotionCurve | 'ressort'): ArchMove {
    return {
      at, ms, curve, fromCamera: { ...camera, target: [...camera.target] as [number, number, number] },
      fromAlt: frame.plateAlt.slice(), fromFold: frame.nodeFold.slice(), fromHidden: frame.plateHidden.slice(),
      fromCapsule: frame.plateCapsule.slice(),
    };
  }

  /**
   * L'image de `plan` à l'instant `t` (ms depuis le début de la phase) : T-A1 pour `entree`, T-A2 pour `sortie`,
   * l'état final pour `repos`. Écrit dans `out`, <b>n'alloue rien</b>.
   */
  frame(plan: ArchitecturePlan, phase: ArchPhaseKind, t: number, out: ArchFrame, now = t,
      transition: ArchTransition | null = null, move: ArchMove | null = null): ArchFrame {
    const { plates, nodes, edges } = this.layout;
    const h = plan.step * plan.lift;
    const seconds = now / 1000;
    for (let i = 0; i < edges.length; i++) {
      out.edgeSweep[i] = plan.edgeInFlow[i];
      out.edgeTravel[i] = seconds;
    }
    if (phase === 'entree') {
      const { rise, pose, trace } = this;
      out.lay = ease('glisse', span(t, 0, ENTRY.layEnd));
      const show = ease('sortie', span(t, 0, ENTRY.riseStart));
      for (let i = 0; i < plates.length; i++) {
        const a = ENTRY.riseStart + plates[i].riseRank * rise.gap;
        out.plateAlt[i] = plates[i].level * h * ease('sortie', span(t, a, a + rise.dur));
        out.plateFill[i] = plan.plateFill[i] * show;
        out.plateLine[i] = plan.plateLine[i] * show;
      }
      for (let i = 0; i < nodes.length; i++) {
        const a = ENTRY.poseStart + nodes[i].order * pose.gap;
        out.nodeScale[i] = ease('rebond', span(t, a, a + pose.dur));
        out.nodeAlpha[i] = plan.nodeAlpha[i];
      }
      for (let i = 0; i < edges.length; i++) {
        const a = ENTRY.traceStart + edges[i].order * trace.gap;
        out.edgeTrace[i] = ease('sortie', span(t, a, a + trace.dur));
        out.edgeAlpha[i] = plan.edgeAlpha[i];
      }
      const spark = ease('sortie', span(t, ENTRY.sparkStart, MOTION_DURATIONS.archEntry));
      out.particles = spark;
      out.labels = spark;
      out.float = NODE_FLOAT * plan.lift;
      this.fold(plan, out, null, now, false);
      return out;
    }
    if (phase === 'sortie') {
      out.lay = 1 - ease('entree', span(t, EXIT.layStart, MOTION_DURATIONS.archExit));
      const down = 1 - ease('entree', span(t, EXIT.riseStart, EXIT.riseEnd));
      for (let i = 0; i < plates.length; i++) {
        out.plateAlt[i] = plates[i].level * h * down;
        out.plateFill[i] = plan.plateFill[i];
        out.plateLine[i] = plan.plateLine[i];
      }
      const scale = 1 - ease('entree', span(t, 0, EXIT.poseEnd));
      for (let i = 0; i < nodes.length; i++) {
        out.nodeScale[i] = scale;
        out.nodeAlpha[i] = plan.nodeAlpha[i];
      }
      const trace = 1 - ease('entree', span(t, EXIT.traceStart, EXIT.traceEnd));
      for (let i = 0; i < edges.length; i++) {
        out.edgeTrace[i] = trace;
        out.edgeAlpha[i] = plan.edgeAlpha[i];
      }
      const spark = 1 - ease('entree', span(t, 0, EXIT.sparkEnd));
      out.particles = spark;
      out.labels = spark;
      out.float = NODE_FLOAT * plan.lift * down;
      this.fold(plan, out, null, now, false);
      return out;
    }
    out.lay = 1;
    for (let i = 0; i < plates.length; i++) {
      out.plateAlt[i] = plates[i].level * h;
      out.plateFill[i] = plan.plateFill[i];
      out.plateLine[i] = plan.plateLine[i];
    }
    for (let i = 0; i < nodes.length; i++) {
      out.nodeScale[i] = 1;
      out.nodeAlpha[i] = plan.nodeAlpha[i];
    }
    for (let i = 0; i < edges.length; i++) {
      out.edgeTrace[i] = 1;
      out.edgeAlpha[i] = plan.edgeAlpha[i];
    }
    out.particles = 1;
    out.labels = 1;
    out.float = NODE_FLOAT * plan.lift;
    if (transition) {
      this.narrate(plan, transition, now, out);
    }
    this.fold(plan, out, move, now);
    return out;
  }

  /**
   * SF-238-10 — T-A3 (altitudes), T-A4 / T-A5 (capsules), et le reste des déplacements : chaque valeur part de celle
   * affichée au début du déplacement et rejoint le plan. <b>N'alloue rien.</b>
   */
  private fold(plan: ArchitecturePlan, out: ArchFrame, move: ArchMove | null, now: number, alt = true): void {
    const { plates, nodes } = this.layout;
    const p = moveProgress(move, now);
    const h = plan.step * plan.lift;
    for (let i = 0; i < plates.length; i++) {
      if (alt) {
        const to = plates[i].level * h;
        const from = move ? move.fromAlt[i] : to;
        out.plateAlt[i] = from + (to - from) * p;
      }
      const hf = move ? move.fromHidden[i] : plan.plateHidden[i];
      out.plateHidden[i] = hf + (plan.plateHidden[i] - hf) * p;
      const cf = move ? move.fromCapsule[i] : plan.plateCapsule[i];
      out.plateCapsule[i] = cf + (plan.plateCapsule[i] - cf) * p;
    }
    for (let i = 0; i < nodes.length; i++) {
      const ff = move ? move.fromFold[i] : plan.nodeFold[i];
      const fold = ff + (plan.nodeFold[i] - ff) * p;
      out.nodeFold[i] = fold;
      // La capsule : le centre du plateau qui rentre le service (celui du plan, ou celui qu'il quitte).
      const home = plan.nodeHome[i];
      const n = nodes[i];
      if (home >= 0) {
        const c = plates[home];
        out.nodeX[i] = n.x + (c.x0 + c.w / 2 - n.x) * fold;
        out.nodeZ[i] = n.z + (c.z0 + c.d / 2 - n.z) * fold;
      } else if (fold > 0 && n.plate >= 0) {
        // Dépli : le service ressort du centre de son plateau porteur le plus extérieur replié auparavant.
        let c = n.plate;
        for (let q = n.plate, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
          if (move && move.fromCapsule[q] > 0) {
            c = q;
          }
        }
        out.nodeX[i] = n.x + (plates[c].x0 + plates[c].w / 2 - n.x) * fold;
        out.nodeZ[i] = n.z + (plates[c].z0 + plates[c].d / 2 - n.z) * fold;
      } else {
        out.nodeX[i] = n.x;
        out.nodeZ[i] = n.z;
      }
    }
  }

  /**
   * SF-238-12 — la phrase entendue change ce qui est allumé (plan `transition.from` ⇒ `plan` à `transition.at`) :
   * T-A8 (0,35 s, `sortie`) pour ce qui s'allume, halo et socle qui pulse une fois ; extinction en 0,9 s
   * (`souffle`) ; T-A7 pour un flux qui s'allume : tracé de la source à la cible segment par segment (150 ms par
   * segment, borné à 0,9 s, `sortie`), puis particules ×2 pendant 2 s — le parcours est intégré, jamais de saut.
   */
  private narrate(plan: ArchitecturePlan, transition: ArchTransition, now: number, out: ArchFrame): void {
    const from = transition.from;
    const dt = Math.max(0, now - transition.at);
    const up = ease('sortie', span(dt, 0, NARRATION.lightMs));
    const down = ease('souffle', span(dt, 0, NARRATION.fadeMs));
    const mix = (a: number, b: number): number => a + (b - a) * (b >= a ? up : down);
    for (let i = 0; i < out.plateFill.length; i++) {
      out.plateFill[i] = mix(from.plateFill[i], plan.plateFill[i]);
      out.plateLine[i] = mix(from.plateLine[i], plan.plateLine[i]);
    }
    const pulse = Math.sin(Math.PI * span(dt, 0, NARRATION.lightMs));
    for (let i = 0; i < out.nodeAlpha.length; i++) {
      out.nodeAlpha[i] = mix(from.nodeAlpha[i], plan.nodeAlpha[i]);
      if (plan.nodeLit[i] && !from.nodeLit[i]) {
        out.nodeScale[i] = 1 + NARRATION.pulse * pulse;
      }
    }
    for (let i = 0; i < out.edgeAlpha.length; i++) {
      out.edgeAlpha[i] = mix(from.edgeAlpha[i], plan.edgeAlpha[i]);
      if (plan.edgeInFlow[i] && !from.edgeInFlow[i]) {
        const sweep = this.sweepMs[i];
        out.edgeSweep[i] = ease('sortie', span(dt, 0, sweep));
        // ×2 pendant 2 s après le tracé : le parcours gagne la durée accélérée écoulée (continu).
        out.edgeTravel[i] += Math.max(0, Math.min(dt - sweep, NARRATION.boostMs)) / 1000;
      } else if (!plan.edgeInFlow[i] && from.edgeInFlow[i]) {
        out.edgeSweep[i] = 1 - down;
      }
    }
  }
}
