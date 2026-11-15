import { ARCH_ICON_PX, ArchPoint, ArchitectureGraph, ArchitectureZoneKind, DASHED_KINDS } from './architecture-graph';

/**
 * F-238 / SF-238-09 — **des coordonnées 2D aux plateaux** (cadrage §8.1). Fichier <b>pur</b> (aucun `three`).
 *
 * <p>Plan au sol : `(x, y)` du graphe ⇒ `(x, z)` en 3D, 1 px = 0,01 u, centré sur l'origine — la vue de dessus et le
 * 2D cadré coïncident donc par construction. Chaque zone est un plateau au niveau `depth + 1` (le sol, niveau 0,
 * porte les acteurs externes) ; son altitude vaut `niveau × Δ` (compact 0,25, éclaté 2,2). Une liaison reprend la
 * polyligne du graphe à l'altitude du plateau commun le plus profond et rejoint ses extrémités par des segments
 * <b>verticaux</b> : jamais de diagonale, ni en plan ni en hauteur.</p>
 */

/** Échelle du plan : un pixel du SVG = 0,01 unité. */
export const ARCH_PX = 0.01;
/**
 * Δ compact (terrasses), éclaté (plateaux empilés), éclaté au téléphone (cadrage §14). Compact relevé de 0,25 à 0,6
 * (retour orchestrateur 2026-11-15) : à 0,25 les terrasses ne se lisaient pas à l'angle par défaut.
 */
export const STEP_COMPACT = 0.6;
export const STEP_EXPLODED = 2.2;
export const STEP_EXPLODED_PHONE = 1.4;
/** Hauteur de vol d'un service au-dessus de son plateau. */
export const NODE_FLOAT = 0.35;
/** Les filaments courent juste au-dessus du plateau (jamais dedans). */
export const FILAMENT_LIFT = 0.004;
/** Taille de l'icône (64 px du SVG) en unités. */
export const ICON_SIZE = ARCH_ICON_PX * ARCH_PX;
/** Flux (cadrage §9.1) : vitesse des particules (u/s) et espacement (u). */
export const FLOW_SPEED = 0.8;
export const FLOW_SPACING = 0.6;
/** En deçà, deux coordonnées sont égales (le graphe est sur une grille de 8 px). */
const SAME_PX = 0.5;

export interface Plate3d {
  index: number;
  id: string;
  kind: ArchitectureZoneKind;
  label: string;
  /** `depth + 1` : le sol est le niveau 0. */
  level: number;
  /** Indice du plateau parent, `-1` à la racine. */
  parent: number;
  /** Coin haut-gauche et taille, en unités du plan. */
  x0: number;
  z0: number;
  w: number;
  d: number;
  dashed: boolean;
  /** Rang de montée en T-A1 : du plus large au plus profond. */
  riseRank: number;
}

export interface Node3d {
  index: number;
  id: string;
  label: string;
  type: string;
  resolved: string | null;
  family: string;
  unknown: boolean;
  /** Plateau porteur, `-1` = sol. */
  plate: number;
  /** Centre de l'icône dans le plan. */
  x: number;
  z: number;
  /** Rang dans l'ordre du flux (sources d'abord) : sert à T-A1 et à la narration. */
  flowRank: number;
  /** Position dans l'ordre de pose (0 = premier posé). */
  order: number;
}

export interface Edge3d {
  index: number;
  id: string;
  from: number;
  to: number;
  label: string;
  bidirectional: boolean;
  /** Polyligne du plan (x, z alternés), orthogonale. */
  plan: Float32Array;
  points: number;
  fromPlate: number;
  toPlate: number;
  /** Le plateau commun le plus profond (`-1` = sol). */
  common: number;
  /** Milieu du plus long segment : place de l'étiquette de flux. */
  labelX: number;
  labelZ: number;
  /** Un segment oblique du graphe a été coupé en L. */
  repaired: boolean;
  /** Position dans l'ordre de tracé (T-A1). */
  order: number;
}

export interface ArchLayout {
  graph: ArchitectureGraph;
  /** Emprise du plan en unités (largeur x, profondeur z). */
  width: number;
  depth: number;
  maxLevel: number;
  plates: Plate3d[];
  nodes: Node3d[];
  edges: Edge3d[];
  /** Segments obliques réparés (graphe corrompu) : 0 attendu. */
  repaired: number;
  plateById: ReadonlyMap<string, number>;
  nodeById: ReadonlyMap<string, number>;
}

/** Un point du graphe (px) dans le plan 3D (u). */
export function toPlan(layout: { graph: ArchitectureGraph }, p: ArchPoint): { x: number; z: number } {
  return { x: (p.x - layout.graph.bounds.w / 2) * ARCH_PX, z: (p.y - layout.graph.bounds.h / 2) * ARCH_PX };
}

/** Coupe en L tout segment oblique (le graphe est censé n'en avoir aucun, G1). */
function orthogonal(points: ArchPoint[]): { pts: ArchPoint[]; repaired: boolean } {
  const out: ArchPoint[] = [points[0]];
  let repaired = false;
  for (let i = 1; i < points.length; i++) {
    const a = out[out.length - 1];
    const b = points[i];
    if (Math.abs(a.x - b.x) > SAME_PX && Math.abs(a.y - b.y) > SAME_PX) {
      out.push({ x: b.x, y: a.y });
      repaired = true;
    }
    out.push(b);
  }
  return { pts: out, repaired };
}

/** Rangs du flux : parcours en largeur depuis les sources (aucune liaison entrante), puis le reste par position. */
function flowRanks(graph: ArchitectureGraph): number[] {
  const index = new Map(graph.nodes.map((n, i) => [n.id, i]));
  const incoming = new Array<number>(graph.nodes.length).fill(0);
  const out: number[][] = graph.nodes.map(() => []);
  for (const e of graph.edges) {
    const f = index.get(e.from) as number;
    const t = index.get(e.to) as number;
    if (f !== t) {
      out[f].push(t);
      incoming[t]++;
    }
  }
  const rank = new Array<number>(graph.nodes.length).fill(-1);
  const byPlace = (a: number, b: number): number => graph.nodes[a].box.x - graph.nodes[b].box.x
    || graph.nodes[a].box.y - graph.nodes[b].box.y;
  let queue = graph.nodes.map((_, i) => i).filter((i) => incoming[i] === 0).sort(byPlace);
  if (queue.length === 0) {
    queue = [graph.nodes.map((_, i) => i).sort(byPlace)[0]];
  }
  for (const i of queue) {
    rank[i] = 0;
  }
  while (queue.length > 0) {
    const next: number[] = [];
    for (const i of queue) {
      for (const j of out[i]) {
        if (rank[j] < 0) {
          rank[j] = rank[i] + 1;
          next.push(j);
        }
      }
    }
    queue = next;
  }
  const max = Math.max(0, ...rank);
  return rank.map((r) => (r < 0 ? max + 1 : r));
}

/** Les plateaux ancêtres d'un plateau (lui compris), du plus profond à la racine. */
function chain(plates: Plate3d[], start: number): number[] {
  const out: number[] = [];
  for (let p = start, guard = 0; p >= 0 && guard < 64; p = plates[p].parent, guard++) {
    out.push(p);
  }
  return out;
}

/** Le graphe posé en 3D, ou `null` s'il est vide. Calculé une fois (D7) : rien ne se recalcule par image. */
export function layoutArchitecture(graph: ArchitectureGraph): ArchLayout | null {
  if (graph.nodes.length === 0) {
    return null;
  }
  const W = graph.bounds.w;
  const H = graph.bounds.h;
  const plateById = new Map(graph.zones.map((z, i) => [z.id, i]));
  const plates: Plate3d[] = graph.zones.map((z, i) => ({
    index: i,
    id: z.id,
    kind: z.kind,
    label: z.label,
    level: Math.max(0, z.depth) + 1,
    parent: plateById.get(z.parent) ?? -1,
    x0: (z.box.x - W / 2) * ARCH_PX,
    z0: (z.box.y - H / 2) * ARCH_PX,
    w: z.box.w * ARCH_PX,
    d: z.box.h * ARCH_PX,
    dashed: DASHED_KINDS.has(z.kind),
    riseRank: 0,
  }));
  [...plates].sort((a, b) => b.w * b.d - a.w * a.d || a.level - b.level || a.index - b.index)
    .forEach((p, rank) => (p.riseRank = rank));

  const ranks = flowRanks(graph);
  const nodes: Node3d[] = graph.nodes.map((n, i) => ({
    index: i,
    id: n.id,
    label: n.label,
    type: n.type,
    resolved: n.unknown ? null : n.resolved,
    family: n.family,
    unknown: n.unknown,
    plate: plateById.get(n.zone) ?? -1,
    x: (n.box.x + n.box.w / 2 - W / 2) * ARCH_PX,
    z: (n.box.y + ARCH_ICON_PX / 2 - H / 2) * ARCH_PX,
    flowRank: ranks[i],
    order: 0,
  }));
  [...nodes].sort((a, b) => a.flowRank - b.flowRank || a.x - b.x || a.z - b.z || a.index - b.index)
    .forEach((n, k) => (n.order = k));
  const nodeById = new Map(nodes.map((n) => [n.id, n.index]));

  let repairedCount = 0;
  const edges: Edge3d[] = graph.edges.map((e, i) => {
    const { pts, repaired } = orthogonal(e.points);
    repairedCount += repaired ? 1 : 0;
    const plan = new Float32Array(pts.length * 2);
    let longest = -1;
    let labelX = 0;
    let labelZ = 0;
    pts.forEach((p, k) => {
      plan[k * 2] = (p.x - W / 2) * ARCH_PX;
      plan[k * 2 + 1] = (p.y - H / 2) * ARCH_PX;
      if (k > 0) {
        const len = Math.abs(p.x - pts[k - 1].x) + Math.abs(p.y - pts[k - 1].y);
        if (len > longest) {
          longest = len;
          labelX = ((p.x + pts[k - 1].x) / 2 - W / 2) * ARCH_PX;
          labelZ = ((p.y + pts[k - 1].y) / 2 - H / 2) * ARCH_PX;
        }
      }
    });
    const from = nodeById.get(e.from) as number;
    const to = nodeById.get(e.to) as number;
    const fromPlate = nodes[from].plate;
    const toPlate = nodes[to].plate;
    const toChain = new Set(chain(plates, toPlate));
    const common = chain(plates, fromPlate).find((p) => toChain.has(p)) ?? -1;
    return {
      index: i, id: e.id, from, to, label: e.label, bidirectional: e.bidirectional, plan, points: pts.length,
      fromPlate, toPlate, common, labelX, labelZ, repaired, order: 0,
    };
  });
  [...edges].sort((a, b) => nodes[a.from].order - nodes[b.from].order || nodes[a.to].order - nodes[b.to].order
    || a.index - b.index).forEach((e, k) => (e.order = k));

  return {
    graph,
    width: W * ARCH_PX,
    depth: H * ARCH_PX,
    maxLevel: Math.max(0, ...plates.map((p) => p.level)),
    plates,
    nodes,
    edges,
    repaired: repairedCount,
    plateById,
    nodeById,
  };
}

/** Nombre de points 3D d'une liaison : la polyligne plus deux raccords verticaux (éventuellement nuls). */
export function pathPoints(edge: Edge3d): number {
  return edge.points + 2;
}

/**
 * Écrit le chemin 3D d'une liaison dans `out` (x, y, z alternés ; {@link pathPoints} points) pour des altitudes de
 * plateau données (`altitude[plateau]`, le sol vaut 0) ; renvoie sa longueur. <b>N'alloue rien</b> (appelé à chaque
 * image pendant les montées).
 */
export function writeEdgePath(edge: Edge3d, altitude: ArrayLike<number>, out: Float32Array, offset = 0): number {
  const yFrom = (edge.fromPlate < 0 ? 0 : altitude[edge.fromPlate]) + FILAMENT_LIFT;
  const yTo = (edge.toPlate < 0 ? 0 : altitude[edge.toPlate]) + FILAMENT_LIFT;
  const yCommon = (edge.common < 0 ? 0 : altitude[edge.common]) + FILAMENT_LIFT;
  const plan = edge.plan;
  const last = edge.points - 1;
  let o = offset;
  out[o++] = plan[0];
  out[o++] = yFrom;
  out[o++] = plan[1];
  for (let k = 0; k <= last; k++) {
    out[o++] = plan[k * 2];
    out[o++] = yCommon;
    out[o++] = plan[k * 2 + 1];
  }
  out[o++] = plan[last * 2];
  out[o++] = yTo;
  out[o++] = plan[last * 2 + 1];
  let length = 0;
  for (let k = 1; k < edge.points + 2; k++) {
    const a = offset + (k - 1) * 3;
    const b = offset + k * 3;
    length += Math.abs(out[b] - out[a]) + Math.abs(out[b + 1] - out[a + 1]) + Math.abs(out[b + 2] - out[a + 2]);
  }
  return length;
}

/**
 * Le point à l'abscisse curviligne `s` d'un chemin écrit par {@link writeEdgePath} (segments orthogonaux : la
 * longueur d'un segment est la somme des écarts) ; écrit dans `out[o..o+2]`, n'alloue rien.
 */
export function pointAt(path: Float32Array, offset: number, count: number, s: number, out: Float32Array,
    o: number): void {
  let rest = Math.max(0, s);
  for (let k = 1; k < count; k++) {
    const a = offset + (k - 1) * 3;
    const b = offset + k * 3;
    const len = Math.abs(path[b] - path[a]) + Math.abs(path[b + 1] - path[a + 1]) + Math.abs(path[b + 2] - path[a + 2]);
    if (rest <= len || k === count - 1) {
      const f = len > 0 ? Math.min(1, rest / len) : 0;
      out[o] = path[a] + (path[b] - path[a]) * f;
      out[o + 1] = path[a + 1] + (path[b + 1] - path[a + 1]) * f;
      out[o + 2] = path[a + 2] + (path[b + 2] - path[a + 2]) * f;
      return;
    }
    rest -= len;
  }
  out[o] = path[offset];
  out[o + 1] = path[offset + 1];
  out[o + 2] = path[offset + 2];
}

/**
 * Les particules d'une liaison à l'instant `seconds` (cadrage §9.1) : elles parcourent le chemin de `from` vers `to`
 * (et aussi de `to` vers `from` si bidirectionnel), espacées de {@link FLOW_SPACING}, à `FLOW_SPEED × speed`.
 * Écrit positions (x, y, z) et éclat (0-1, nul aux extrémités) à partir de `start` ; renvoie le nouvel indice libre,
 * borné par `max`. <b>N'alloue rien.</b>
 */
export function writeParticles(path: Float32Array, offset: number, count: number, length: number,
    bidirectional: boolean, seconds: number, speed: number, positions: Float32Array, glow: Float32Array,
    start: number, max: number): number {
  if (length <= 0 || speed <= 0) {
    return start;
  }
  const shift = (seconds * FLOW_SPEED * speed) % FLOW_SPACING;
  let i = start;
  for (let s = shift; s < length && i < max; s += FLOW_SPACING) {
    pointAt(path, offset, count, s, positions, i * 3);
    glow[i] = Math.sin(Math.PI * (s / length));
    i++;
    if (bidirectional && i < max) {
      pointAt(path, offset, count, length - s, positions, i * 3);
      glow[i] = Math.sin(Math.PI * (s / length));
      i++;
    }
  }
  return i;
}
