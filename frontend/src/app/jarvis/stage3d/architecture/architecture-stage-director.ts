import { HOLO_ALPHA, HoloRole, MOTION_DURATIONS, ease } from '../jarvis-motion';
import { ArchLayout, NODE_FLOAT, STEP_COMPACT, STEP_EXPLODED, STEP_EXPLODED_PHONE } from './architecture-layout-3d';

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
/** Le 2D cadré : contenu `contain`, centré, marge de 5 % de chaque côté. */
export const SVG_FIT = 0.9;
/** Vue d'ensemble : marge minimale au bord du cadre (le schéma occupe le centre, en grand). */
export const ENSEMBLE_MARGIN = 0.04;
/** Au plus trois éléments allumés à la fois (règle F-234 D2). */
export const MAX_LIT = 3;
/** Étiquettes (cadrage §8.2, §14). */
export const LABEL_ALL_UNDER = 24;
export const LABEL_NEIGHBOURS_UNDER = 40;
export const PHONE_LABELS = 16;

export type ArchView = 'ensemble' | 'dessus';
export type ArchPhaseKind = 'entree' | 'repos' | 'sortie';

export interface ArchState {
  view: ArchView;
  exploded: boolean;
  /** Le service ou la zone au centre de l'attention (id du graphe), sinon `null`. */
  focus: string | null;
  /** Ce dont Jarvis parle (ids de services, zones ou liaisons) : au plus {@link MAX_LIT}. */
  lit: readonly string[];
  phone: boolean;
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
  /** Vitesse des particules par liaison (× {@link FLOW_SPEED} ; 0 = pas de flux). */
  edgeFlow: Float32Array;
  nodeLabel: boolean[];
  plateLabel: boolean[];
  /** Le héros (règle F-236 §7.3-6) : ce dont Jarvis parle, sinon le focus. */
  hero: string | null;
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
  particles: number;
  labels: number;
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

/** La caméra d'une vue (T-A9). */
export function cameraFor(layout: ArchLayout, state: ArchState, viewport: Viewport): ArchCamera {
  if (state.view === 'dessus') {
    const tan = Math.tan((ARCH_FOV * Math.PI) / 360);
    // Zénith : la largeur visible au sol est exactement celle du 2D cadré.
    const fit = svgFit(layout.graph.bounds, viewport);
    const distance = (viewport.height * 0.01) / (2 * fit.scale * tan);
    return { target: [0, 0, 0], azimuth: 0, elevation: Math.PI / 2, distance, fov: ARCH_FOV };
  }
  const height = layout.maxLevel * stepOf(state) + NODE_FLOAT;
  let camera: ArchCamera = {
    target: [0, height / 2, 0], azimuth: ENSEMBLE_AZIMUTH, elevation: ENSEMBLE_ELEVATION, distance: 1, fov: ARCH_FOV,
  };
  const hw = layout.width / 2;
  const hd = layout.depth / 2;
  const corners = [[-hw, 0, -hd], [hw, 0, -hd], [-hw, 0, hd], [hw, 0, hd], [-hw, height, -hd], [hw, height, -hd],
    [-hw, height, hd], [hw, height, hd]];
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
  const mx = viewport.width * ENSEMBLE_MARGIN;
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
  // Éclaté : le volume plus haut est recadré (la caméra recule d'elle-même, T-A3).
  return camera;
}

export class ArchitectureStageDirector {
  /** Cascades de T-A1, calculées une fois (le chemin d'image n'alloue rien). */
  private readonly rise: { gap: number; dur: number };
  private readonly pose: { gap: number; dur: number };
  private readonly trace: { gap: number; dur: number };

  constructor(readonly layout: ArchLayout) {
    this.rise = cascade(layout.plates.length, ENTRY.riseEnd - ENTRY.riseStart, ENTRY.riseStagger);
    this.pose = cascade(layout.nodes.length, ENTRY.poseEnd - ENTRY.poseStart, ENTRY.poseStagger);
    this.trace = cascade(layout.edges.length, ENTRY.traceEnd - ENTRY.traceStart, ENTRY.traceStagger);
  }

  /** Le plan d'un état : alloué au changement d'état seulement, jamais par image. */
  plan(state: ArchState, viewport: Viewport): ArchitecturePlan {
    const { plates, nodes, edges } = this.layout;
    const lit = new Set(state.lit.slice(0, MAX_LIT));
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
    edges.forEach((e, i) => {
      const on = lit.has(e.id);
      edgeAlpha[i] = on ? HOLO_ALPHA.lit : rest('edge', i) ? HOLO_ALPHA.active : HOLO_ALPHA.context;
      edgeRole.push(on ? 'core' : 'normal');
      // T-A7 : un flux allumé court deux fois plus vite ; hors focus, plus de particules.
      edgeFlow[i] = on ? 2 : rest('edge', i) ? 1 : 0;
    });

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
    const zoneBudget = state.phone ? Math.max(0, PHONE_LABELS - nodeLabel.filter(Boolean).length) : Infinity;
    const plateLabel = plates.map((p) => lit.has(p.id) || p.id === focus || p.riseRank < zoneBudget);

    return {
      state,
      camera: cameraFor(this.layout, state, viewport),
      step: stepOf(state),
      lift: state.view === 'dessus' ? 0 : 1,
      plateFill, plateLine, plateRole, nodeAlpha, nodeRole, edgeAlpha, edgeRole, edgeFlow, nodeLabel, plateLabel,
      hero: state.lit[0] ?? focus,
    };
  }

  /** Les tampons d'image à la taille du graphe (une fois, à la pose de la scène). */
  newFrame(): ArchFrame {
    const { plates, nodes, edges } = this.layout;
    return {
      lay: 1, plateAlt: new Float32Array(plates.length), plateFill: new Float32Array(plates.length),
      plateLine: new Float32Array(plates.length), float: NODE_FLOAT, nodeScale: new Float32Array(nodes.length),
      nodeAlpha: new Float32Array(nodes.length), edgeTrace: new Float32Array(edges.length),
      edgeAlpha: new Float32Array(edges.length), particles: 1, labels: 1,
    };
  }

  /**
   * L'image de `plan` à l'instant `t` (ms depuis le début de la phase) : T-A1 pour `entree`, T-A2 pour `sortie`,
   * l'état final pour `repos`. Écrit dans `out`, <b>n'alloue rien</b>.
   */
  frame(plan: ArchitecturePlan, phase: ArchPhaseKind, t: number, out: ArchFrame): ArchFrame {
    const { plates, nodes, edges } = this.layout;
    const h = plan.step * plan.lift;
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
    return out;
  }
}
