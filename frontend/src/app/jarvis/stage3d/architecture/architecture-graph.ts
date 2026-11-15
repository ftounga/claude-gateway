/**
 * F-238 / SF-238-09 — **le graphe d'architecture v1** (cadrage §5.1), tel que `diagram-renderer/cadre.js` le produit
 * (`schemas/architecture-graph-v1.schema.json`) : structure ET mise en page cadrée, en pixels du SVG. Fichier
 * <b>pur</b> : la 3D reprend ces coordonnées telles quelles, elle ne fait aucune mise en page (D7).
 */

export type ArchitectureZoneKind = 'cloud' | 'account' | 'region' | 'vpc' | 'az' | 'subnet_public' | 'subnet_private'
  | 'asg' | 'security_group' | 'onprem' | 'generic';

export interface ArchBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface ArchPoint {
  x: number;
  y: number;
}

export interface ArchitectureZone {
  id: string;
  kind: ArchitectureZoneKind;
  label: string;
  /** Zone parente, `''` à la racine. */
  parent: string;
  depth: number;
  box: ArchBox;
  inferred: boolean;
}

export interface ArchitectureNode {
  id: string;
  type: string;
  /** Chemin d'icône réellement utilisé ; `null` ⇒ boîte neutre. */
  resolved: string | null;
  label: string;
  /** Zone du nœud, `''` hors de toute zone (acteur externe). */
  zone: string;
  family: string;
  unknown: boolean;
  box: ArchBox;
}

export interface ArchitectureEdge {
  id: string;
  from: string;
  to: string;
  label: string;
  bidirectional: boolean;
  /** Polyligne orthogonale (garantie G1), de `from` vers `to`. */
  points: ArchPoint[];
  labelBox: ArchBox | null;
}

export interface ArchitectureGraph {
  version: 1;
  title: string;
  direction: 'LR' | 'TB';
  bounds: { w: number; h: number };
  zones: ArchitectureZone[];
  nodes: ArchitectureNode[];
  edges: ArchitectureEdge[];
  notices: string[];
}

/** Bornes F-142 (cadrage §5.1) : au-delà, la 3D tronque (les premiers éléments gardés). */
export const ARCH_MAX_NODES = 60;
export const ARCH_MAX_ZONES = 24;
export const ARCH_MAX_EDGES = 120;

/** La taille de l'icône d'un nœud dans le SVG cadré (`cadre.js`, `ICON`) : posée en haut, centrée. */
export const ARCH_ICON_PX = 64;

/** Les `kind` dessinés en pointillé (cadrage §9.1) ; les autres en trait continu. */
export const DASHED_KINDS: ReadonlySet<ArchitectureZoneKind> = new Set(['region', 'az', 'asg']);

function finite(...values: unknown[]): boolean {
  return values.every((v) => typeof v === 'number' && Number.isFinite(v));
}

function boxOk(box: ArchBox | null | undefined): box is ArchBox {
  return !!box && finite(box.x, box.y, box.w, box.h) && box.w >= 0 && box.h >= 0;
}

/**
 * Le graphe lisible par la 3D, ou `null` (version ≠ 1, aucun nœud, bornes absentes) : l'appelant garde le repli 2D.
 * Les éléments mal formés sont écartés, les bornes F-142 appliquées.
 */
export function readArchitectureGraph(raw: unknown): ArchitectureGraph | null {
  const g = raw as Partial<ArchitectureGraph> | null;
  if (!g || g.version !== 1 || !g.bounds || !finite(g.bounds.w, g.bounds.h) || g.bounds.w <= 0 || g.bounds.h <= 0) {
    return null;
  }
  const nodes = (Array.isArray(g.nodes) ? g.nodes : []).filter((n) => !!n && typeof n.id === 'string'
    && boxOk(n.box)).slice(0, ARCH_MAX_NODES);
  if (nodes.length === 0) {
    return null;
  }
  const zones = (Array.isArray(g.zones) ? g.zones : []).filter((z) => !!z && typeof z.id === 'string'
    && boxOk(z.box)).slice(0, ARCH_MAX_ZONES);
  const ids = new Set(nodes.map((n) => n.id));
  const edges = (Array.isArray(g.edges) ? g.edges : []).filter((e) => !!e && ids.has(e.from) && ids.has(e.to)
    && Array.isArray(e.points) && e.points.length >= 2 && e.points.every((p) => finite(p?.x, p?.y)))
    .slice(0, ARCH_MAX_EDGES);
  return {
    version: 1,
    title: typeof g.title === 'string' ? g.title : '',
    direction: g.direction === 'TB' ? 'TB' : 'LR',
    bounds: { w: g.bounds.w, h: g.bounds.h },
    zones,
    nodes,
    edges,
    notices: Array.isArray(g.notices) ? g.notices.filter((n) => typeof n === 'string') : [],
  };
}
