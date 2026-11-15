import { ArchitectureGraph, readArchitectureGraph } from './architecture-graph';
import { TOUT_GRAPH, TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import {
  ARCH_PX, ArchLayout, FLOW_SPACING, FLOW_SPEED, STEP_COMPACT, STEP_EXPLODED, layoutArchitecture, pathPoints,
  writeEdgePath, writeParticles,
} from './architecture-layout-3d';

/** Les altitudes des plateaux pour un Δ donné (état final). */
function altitudes(layout: ArchLayout, step: number): Float32Array {
  return Float32Array.from(layout.plates.map((p) => p.level * step));
}

/** Les segments 3D non orthogonaux (plus d'un axe qui varie) de toutes les liaisons. */
function obliqueSegments(layout: ArchLayout, step: number): number {
  const alt = altitudes(layout, step);
  let bad = 0;
  for (const e of layout.edges) {
    const path = new Float32Array(pathPoints(e) * 3);
    writeEdgePath(e, alt, path);
    for (let k = 1; k < pathPoints(e); k++) {
      const moving = [0, 1, 2].filter((axis) => Math.abs(path[k * 3 + axis] - path[(k - 1) * 3 + axis]) > 1e-6);
      bad += moving.length > 1 ? 1 : 0;
    }
  }
  return bad;
}

describe('F-238 / SF-238-09 — des coordonnées 2D aux plateaux', () => {
  const graphs: [string, ArchitectureGraph][] = [['3-tiers', TROIS_TIERS_GRAPH], ['tout', TOUT_GRAPH],
    ['60 services', sixtyNodesGraph()]];

  it('le plan reprend les coordonnées du graphe (1 px = 0,01 u, centré)', () => {
    const layout = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    const { w, h } = TROIS_TIERS_GRAPH.bounds;
    const zone = TROIS_TIERS_GRAPH.zones[2];
    const plate = layout.plates[2];
    expect(plate.x0).toBeCloseTo((zone.box.x - w / 2) * ARCH_PX, 6);
    expect(plate.z0).toBeCloseTo((zone.box.y - h / 2) * ARCH_PX, 6);
    expect(plate.w).toBeCloseTo(zone.box.w * ARCH_PX, 6);
    const rds = layout.nodes[layout.nodeById.get('rdsa') as number];
    // Centre de l'icône 64 px, posée en haut de la boîte du nœud (cadre.js, shapeOf).
    expect(rds.x).toBeCloseTo((976 + 64 - w / 2) * ARCH_PX, 6);
    expect(rds.z).toBeCloseTo((464 + 32 - h / 2) * ARCH_PX, 6);
  });

  it('plateau au niveau depth + 1, service sur le plateau de sa zone, acteur externe au sol', () => {
    const layout = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    expect(layout.plates.find((p) => p.id === 'cloud')?.level).toBe(1);
    expect(layout.plates.find((p) => p.id === 'priva')?.level).toBe(5);
    expect(layout.nodes.find((n) => n.id === 'rdsa')?.plate).toBe(layout.plateById.get('priva') as number);
    expect(layout.nodes.find((n) => n.id === 'users')?.plate).toBe(-1);
  });

  it('pointillés pour region / az / asg, trait continu sinon', () => {
    const layout = layoutArchitecture(TOUT_GRAPH) as ArchLayout;
    for (const p of layout.plates) {
      expect(p.dashed).withContext(p.kind).toBe(['region', 'az', 'asg'].includes(p.kind));
    }
  });

  for (const [name, graph] of graphs) {
    it(`${name} : 0 segment non orthogonal, en compact et en éclaté`, () => {
      const layout = layoutArchitecture(graph) as ArchLayout;
      expect(layout.repaired).toBe(0);
      expect(obliqueSegments(layout, STEP_COMPACT)).toBe(0);
      expect(obliqueSegments(layout, STEP_EXPLODED)).toBe(0);
    });
  }

  it('changement de plateau : segments verticaux aux extrémités, trajet à l\'altitude du plateau commun', () => {
    const layout = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    const e = layout.edges.find((x) => x.id === 'e2') as ArchLayout['edges'][number]; // ALB (vpc) → Web A (priva)
    expect(layout.plates[e.common].id).toBe('vpc');
    const alt = altitudes(layout, STEP_EXPLODED);
    const path = new Float32Array(pathPoints(e) * 3);
    writeEdgePath(e, alt, path);
    const last = (pathPoints(e) - 1) * 3;
    // Départ : vertical (x, z inchangés) ; arrivée : remonte jusqu'au plateau du sous-réseau.
    expect(path[0]).toBe(path[3]);
    expect(path[2]).toBe(path[5]);
    expect(path[last - 3]).toBe(path[last]);
    expect(path[last + 1]).toBeCloseTo(layout.plates[layout.plateById.get('priva') as number].level * STEP_EXPLODED
      + 0.004, 5);
    expect(path[4]).toBeCloseTo(layout.plates[e.common].level * STEP_EXPLODED + 0.004, 5);
  });

  it('un segment oblique (graphe corrompu) est coupé en L et compté', () => {
    const g = structuredClone(TROIS_TIERS_GRAPH);
    g.edges[4].points = [{ x: 880, y: 496 }, { x: 1008, y: 560 }];
    const layout = layoutArchitecture(g) as ArchLayout;
    expect(layout.repaired).toBe(1);
    expect(obliqueSegments(layout, STEP_EXPLODED)).toBe(0);
  });

  it('graphe illisible ⇒ null (le repli 2D reste) ; liaison pendante écartée ; bornes F-142', () => {
    expect(readArchitectureGraph(null)).toBeNull();
    expect(readArchitectureGraph({ ...TROIS_TIERS_GRAPH, version: 2 })).toBeNull();
    expect(readArchitectureGraph({ ...TROIS_TIERS_GRAPH, nodes: [] })).toBeNull();
    const dangling = readArchitectureGraph({ ...TROIS_TIERS_GRAPH, edges: [...TROIS_TIERS_GRAPH.edges,
      { id: 'x', from: 'alb', to: 'nulle-part', label: '', bidirectional: false, points: [{ x: 0, y: 0 },
        { x: 0, y: 8 }], labelBox: null }] });
    expect(dangling?.edges.length).toBe(TROIS_TIERS_GRAPH.edges.length);
    const many = sixtyNodesGraph();
    many.nodes.push({ ...many.nodes[0], id: 'trop' });
    expect(readArchitectureGraph(many)?.nodes.length).toBe(60);
  });

  it('nœud dans une zone inconnue : posé au sol, sans erreur', () => {
    const g = structuredClone(TROIS_TIERS_GRAPH);
    g.nodes[3].zone = 'zone-fantome';
    const layout = layoutArchitecture(g) as ArchLayout;
    expect(layout.nodes[3].plate).toBe(-1);
  });

  it('ordre du flux : les utilisateurs d\'abord, l\'ALB avant les instances, les bases ensuite', () => {
    const layout = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    const rank = (id: string): number => layout.nodes[layout.nodeById.get(id) as number].flowRank;
    expect(rank('users')).toBe(0);
    expect(rank('alb')).toBeLessThan(rank('ec2a'));
    expect(rank('ec2a')).toBeLessThan(rank('rdsa'));
  });
});

describe('F-238 / SF-238-09 — les particules suivent le sens du flux', () => {
  const layout = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
  const alt = altitudes(layout, STEP_COMPACT);

  function setup(id: string): { path: Float32Array; length: number; count: number } {
    const e = layout.edges.find((x) => x.id === id) as ArchLayout['edges'][number];
    const path = new Float32Array(pathPoints(e) * 3);
    const length = writeEdgePath(e, alt, path);
    return { path, length, count: pathPoints(e) };
  }

  /** L'abscisse curviligne d'une particule sur le chemin (segments orthogonaux). */
  function arcOf(path: Float32Array, count: number, p: Float32Array, i: number): number {
    let walked = 0;
    let best = Infinity;
    let arc = 0;
    for (let k = 1; k < count; k++) {
      const o0 = (k - 1) * 3;
      const o1 = k * 3;
      const len = Math.abs(path[o1] - path[o0]) + Math.abs(path[o1 + 1] - path[o0 + 1])
        + Math.abs(path[o1 + 2] - path[o0 + 2]);
      // Distance du point au segment (axe unique) et position le long.
      let off = 0;
      let along = 0;
      for (let ax = 0; ax < 3; ax++) {
        const lo = Math.min(path[o0 + ax], path[o1 + ax]);
        const hi = Math.max(path[o0 + ax], path[o1 + ax]);
        const v = p[i * 3 + ax];
        off += v < lo ? lo - v : v > hi ? v - hi : 0;
        along += Math.abs(Math.min(Math.max(v, lo), hi) - path[o0 + ax]);
      }
      if (off < best - 1e-6) {
        best = off;
        arc = walked + along;
      }
      walked += len;
    }
    return arc;
  }

  it('de from vers to : l\'abscisse de chaque particule croît avec le temps', () => {
    const s = setup('e7'); // Web A → S3 : chemin à six coudes
    const a = new Float32Array(600);
    const b = new Float32Array(600);
    const glow = new Float32Array(200);
    const n1 = writeParticles(s.path, 0, s.count, s.length, false, 1.0, 1, a, glow, 0, 200);
    const n2 = writeParticles(s.path, 0, s.count, s.length, false, 1.1, 1, b, glow, 0, 200);
    expect(n1).toBeGreaterThan(3);
    // 0,1 s × 0,8 u/s = 0,08 u, sans franchir l'espacement : la particule i reste la même.
    expect(FLOW_SPEED * 0.1).toBeLessThan(FLOW_SPACING);
    for (let i = 0; i < Math.min(n1, n2); i++) {
      expect(arcOf(s.path, s.count, b, i) - arcOf(s.path, s.count, a, i)).toBeCloseTo(0.08, 3);
    }
  });

  it('bidirectionnel : les deux sens sont parcourus, autant de particules dans chaque sens', () => {
    const tout = layoutArchitecture(TOUT_GRAPH) as ArchLayout;
    const e = tout.edges.find((x) => x.bidirectional) as ArchLayout['edges'][number];
    const ta = Float32Array.from(tout.plates.map((p) => p.level * STEP_COMPACT));
    const count = pathPoints(e);
    const path = new Float32Array(count * 3);
    const length = writeEdgePath(e, ta, path);
    const a = new Float32Array(600);
    const b = new Float32Array(600);
    const glow = new Float32Array(200);
    const n = writeParticles(path, 0, count, length, true, 2, 1, a, glow, 0, 200);
    const m = writeParticles(path, 0, count, length, true, 2.1, 1, b, glow, 0, 200);
    let forward = 0;
    let backward = 0;
    for (let i = 0; i < Math.min(n, m); i++) {
      const d = arcOf(path, count, b, i) - arcOf(path, count, a, i);
      forward += d > 0.07 ? 1 : 0;
      backward += d < -0.07 ? 1 : 0;
    }
    expect(forward).toBeGreaterThan(0);
    expect(backward).toBeGreaterThan(0);
    expect(Math.abs(forward - backward)).toBeLessThanOrEqual(1);
  });

  it('pool borné, vitesse nulle = aucune particule, tampons réutilisés (aucune allocation)', () => {
    const s = setup('e7');
    const pos = new Float32Array(15);
    const glow = new Float32Array(5);
    expect(writeParticles(s.path, 0, s.count, s.length, false, 0, 1, pos, glow, 0, 5)).toBe(5);
    expect(writeParticles(s.path, 0, s.count, s.length, false, 0, 0, pos, glow, 0, 5)).toBe(0);
    const before = pos;
    writeParticles(s.path, 0, s.count, s.length, false, 3, 1, pos, glow, 0, 5);
    expect(pos).toBe(before);
  });
});
