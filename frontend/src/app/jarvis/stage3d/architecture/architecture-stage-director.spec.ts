import { HOLO_ALPHA, MOTION_DURATIONS } from '../jarvis-motion';
import { ARCH_ICON_PX, ArchitectureGraph } from './architecture-graph';
import { TOUT_GRAPH, TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import { ArchLayout, layoutArchitecture } from './architecture-layout-3d';
import {
  ArchState, ArchitectureStageDirector, DEFAULT_ARCH_STATE, ENTRY, NARRATION, Viewport, projectToScreen, svgToScreen,
} from './architecture-stage-director';

const PC: Viewport = { width: 1920, height: 1080 };
const PHONE: Viewport = { width: 390, height: 844 };

function director(graph: ArchitectureGraph): ArchitectureStageDirector {
  return new ArchitectureStageDirector(layoutArchitecture(graph) as ArchLayout);
}

/** Les points remarquables du 2D cadré : coins de zones, centres d'icônes, coudes de liaisons (px du graphe). */
function landmarks(graph: ArchitectureGraph): { x: number; y: number }[] {
  const out: { x: number; y: number }[] = [];
  for (const z of graph.zones) {
    out.push({ x: z.box.x, y: z.box.y }, { x: z.box.x + z.box.w, y: z.box.y + z.box.h });
  }
  for (const n of graph.nodes) {
    out.push({ x: n.box.x + n.box.w / 2, y: n.box.y + ARCH_ICON_PX / 2 });
  }
  for (const e of graph.edges) {
    out.push(...e.points);
  }
  return out;
}

describe('F-238 / SF-238-09 — le directeur de la scène d\'architecture', () => {
  for (const [name, graph] of [['3-tiers', TROIS_TIERS_GRAPH], ['tout', TOUT_GRAPH]] as const) {
    for (const viewport of [PC, PHONE]) {
      it(`${name} ${viewport.width}×${viewport.height} : vue de dessus superposable au 2D cadré (≤ 2 px)`, () => {
        const d = director(graph);
        const state: ArchState = { ...DEFAULT_ARCH_STATE, view: 'dessus', exploded: true,
          phone: viewport === PHONE };
        const plan = d.plan(state, viewport);
        const frame = d.frame(plan, 'repos', 0, d.newFrame());
        // Plateaux couchés en vue de dessus (D-09-1) : aucune altitude, aucun vol.
        expect(Math.max(...frame.plateAlt)).toBe(0);
        expect(frame.float).toBe(0);
        const { w, h } = graph.bounds;
        let worst = 0;
        for (const p of landmarks(graph)) {
          // Au sol (+ 4 mm des filaments) : la pire hauteur posée par la scène en vue de dessus.
          const screen = projectToScreen(plan.camera, viewport, (p.x - w / 2) * 0.01, 0.004, (p.y - h / 2) * 0.01);
          const svg = svgToScreen(graph.bounds, viewport, p.x, p.y);
          worst = Math.max(worst, Math.hypot(screen.x - svg.x, screen.y - svg.y));
        }
        expect(worst).toBeLessThanOrEqual(2);
      });
    }
  }

  it('vue d\'ensemble : élévation 32°, azimut 35°, tout le schéma dans le cadre ; éclaté = caméra 20 % plus loin', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const plan = d.plan(DEFAULT_ARCH_STATE, PC);
    expect(plan.camera.elevation * 180 / Math.PI).toBeCloseTo(32, 6);
    expect(plan.camera.azimuth * 180 / Math.PI).toBeCloseTo(35, 6);
    const { w, h } = TROIS_TIERS_GRAPH.bounds;
    for (const [x, y] of [[0, 0], [w, 0], [0, h], [w, h]]) {
      const s = projectToScreen(plan.camera, PC, (x - w / 2) * 0.01, 0, (y - h / 2) * 0.01);
      expect(s.x).toBeGreaterThan(0);
      expect(s.x).toBeLessThan(PC.width);
      expect(s.y).toBeGreaterThan(0);
      expect(s.y).toBeLessThan(PC.height);
    }
    const exploded = d.plan({ ...DEFAULT_ARCH_STATE, exploded: true }, PC);
    expect(exploded.step).toBe(2.2);
    expect(d.plan({ ...DEFAULT_ARCH_STATE, exploded: true, phone: true }, PHONE).step).toBe(1.4);
    expect(exploded.camera.distance).toBeGreaterThan(plan.camera.distance);
  });

  it('T-A1 : à 0 ms le panneau 2D est debout et éteint, à 2 200 ms tout est posé', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const plan = d.plan(DEFAULT_ARCH_STATE, PC);
    const f = d.frame(plan, 'entree', 0, d.newFrame());
    expect(f.lay).toBe(0);
    expect(Math.max(...f.plateAlt)).toBe(0);
    expect(Math.max(...f.nodeScale)).toBe(0);
    expect(Math.max(...f.edgeTrace)).toBe(0);
    expect(f.particles).toBe(0);
    expect(f.labels).toBe(0);
    const end = d.frame(plan, 'entree', MOTION_DURATIONS.archEntry, d.newFrame());
    const rest = d.frame(plan, 'repos', 0, d.newFrame());
    expect(end.lay).toBe(1);
    expect(Array.from(end.plateAlt)).toEqual(Array.from(rest.plateAlt));
    expect(Array.from(end.nodeScale)).toEqual(Array.from(rest.nodeScale));
    expect(Array.from(end.edgeTrace)).toEqual(Array.from(rest.edgeTrace));
    expect(end.particles).toBe(1);
    expect(MOTION_DURATIONS.archEntry).toBe(2200);
  });

  it('T-A1 : couché à 400 ms ; plateaux du plus large au plus profond ; services dans l\'ordre du flux', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const layout = d.layout;
    const plan = d.plan(DEFAULT_ARCH_STATE, PC);
    expect(d.frame(plan, 'entree', ENTRY.layEnd, d.newFrame()).lay).toBe(1);
    const mid = d.frame(plan, 'entree', 600, d.newFrame());
    const byRank = [...layout.plates].sort((a, b) => a.riseRank - b.riseRank);
    const rise = byRank.map((p) => mid.plateAlt[p.index] / (p.level * plan.step));
    for (let i = 1; i < rise.length; i++) {
      expect(rise[i]).toBeLessThanOrEqual(rise[i - 1] + 1e-9);
    }
    expect(byRank[0].id).toBe('cloud');
    const posing = d.frame(plan, 'entree', 800, d.newFrame());
    const users = layout.nodeById.get('users') as number;
    const rds = layout.nodeById.get('rdsb') as number;
    expect(posing.nodeScale[users]).toBeGreaterThan(posing.nodeScale[rds]);
    // Rien ne bouge avant son créneau : services après 700 ms, filaments après 1 100 ms.
    expect(Math.max(...d.frame(plan, 'entree', ENTRY.poseStart, d.newFrame()).nodeScale)).toBe(0);
    expect(Math.max(...d.frame(plan, 'entree', ENTRY.traceStart, d.newFrame()).edgeTrace)).toBe(0);
  });

  it('T-A2 : 600 ms, particules éteintes d\'abord, services rentrés, plateaux couchés en panneau', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const plan = d.plan(DEFAULT_ARCH_STATE, PC);
    const early = d.frame(plan, 'sortie', 150, d.newFrame());
    expect(early.particles).toBe(0);
    expect(Math.max(...early.plateAlt)).toBeGreaterThan(0);
    const end = d.frame(plan, 'sortie', MOTION_DURATIONS.archExit, d.newFrame());
    expect(end.lay).toBe(0);
    expect(Math.max(...end.plateAlt)).toBe(0);
    expect(Math.max(...end.nodeScale)).toBe(0);
    expect(MOTION_DURATIONS.archExit).toBe(600);
  });

  it('paliers de transparence F-236 seulement ; un focus met le reste en contexte ; allumés en blanc chaud', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const allowed = Object.values(HOLO_ALPHA) as number[];
    const plan = d.plan({ ...DEFAULT_ARCH_STATE, focus: 'alb', lit: ['alb', 'e1'] }, PC);
    for (const v of [...plan.plateFill, ...plan.plateLine, ...plan.nodeAlpha, ...plan.edgeAlpha]) {
      expect(allowed.some((a) => Math.abs(a - v) < 1e-6)).withContext(String(v)).toBeTrue();
    }
    const at = (id: string): number => d.layout.nodeById.get(id) as number;
    expect(plan.nodeAlpha[at('alb')]).toBe(HOLO_ALPHA.lit);
    expect(plan.nodeRole[at('alb')]).toBe('core');
    expect(plan.nodeAlpha[at('rdsa')]).toBeCloseTo(HOLO_ALPHA.context, 6); // hors focus
    expect(plan.nodeAlpha[at('ec2a')]).toBeCloseTo(HOLO_ALPHA.active, 6); // voisin de l'ALB
    expect(plan.nodeRole.filter((r) => r === 'core').length + plan.edgeRole.filter((r) => r === 'core').length)
      .toBe(2);
    const e1 = d.layout.edges.find((e) => e.id === 'e1')?.index as number;
    expect(plan.edgeInFlow[e1]).toBe(1);
    expect(plan.hero).toBe('alb');
  });

  it('étiquettes : toutes jusqu\'à 24 services ; au-delà de 40, le focus seul ; téléphone ≤ 16', () => {
    const small = director(TOUT_GRAPH).plan(DEFAULT_ARCH_STATE, PC);
    expect(small.nodeLabel.every(Boolean)).toBeTrue();
    const big = director(sixtyNodesGraph());
    const plan = big.plan({ ...DEFAULT_ARCH_STATE, focus: 'n000' }, PC);
    expect(plan.nodeLabel.filter(Boolean).length).toBe(1);
    const phone = director(TOUT_GRAPH).plan({ ...DEFAULT_ARCH_STATE, phone: true }, PHONE);
    expect(phone.nodeLabel.filter(Boolean).length + phone.plateLabel.filter(Boolean).length)
      .toBeLessThanOrEqual(16);
  });

  it('déterministe : même graphe + même état + même instant ⇒ même image ; tampons réutilisés', () => {
    const d = director(TOUT_GRAPH);
    const plan = d.plan(DEFAULT_ARCH_STATE, PC);
    const out = d.newFrame();
    const a = d.frame(plan, 'entree', 1234, out);
    const snapshot = [Array.from(a.plateAlt), Array.from(a.nodeScale), Array.from(a.edgeTrace)];
    const b = d.frame(plan, 'entree', 1234, out);
    expect(b).toBe(out);
    expect(b.plateAlt).toBe(out.plateAlt);
    expect([Array.from(b.plateAlt), Array.from(b.nodeScale), Array.from(b.edgeTrace)]).toEqual(snapshot);
  });

  describe('SF-238-12 — T-A7 et T-A8 (la phrase entendue)', () => {
    const d = director(TROIS_TIERS_GRAPH);
    const before = d.plan(DEFAULT_ARCH_STATE, PC);
    const after = d.plan({ ...DEFAULT_ARCH_STATE, lit: ['alb'], flow: ['e1'] }, PC);
    const at = 10_000;
    const alb = d.layout.nodeById.get('alb') as number;
    const e1 = d.layout.edges.find((e) => e.id === 'e1')?.index as number;
    const f = (now: number) => d.frame(after, 'repos', now, d.newFrame(), now, { from: before, at });

    it('T-A8 : le halo monte dès la première image (≤ 100 ms), atteint « allumé » à 350 ms, le socle pulse une fois', () => {
      const first = f(at + 16);
      expect(first.nodeAlpha[alb]).toBeGreaterThan(before.nodeAlpha[alb]);
      expect(first.nodeScale[alb]).toBeGreaterThan(1);
      expect(f(at + NARRATION.lightMs).nodeAlpha[alb]).toBeCloseTo(HOLO_ALPHA.lit, 6);
      expect(f(at + NARRATION.lightMs).nodeScale[alb]).toBeCloseTo(1, 6);
    });

    it('extinction en 0,9 s (souffle) quand la phrase suivante ne le cite plus', () => {
      const off = (now: number) => d.frame(before, 'repos', now, d.newFrame(), now, { from: after, at });
      expect(off(at + 450).nodeAlpha[alb]).toBeGreaterThan(before.nodeAlpha[alb]);
      expect(off(at + 450).nodeAlpha[alb]).toBeLessThan(HOLO_ALPHA.lit);
      expect(off(at + NARRATION.fadeMs).nodeAlpha[alb]).toBeCloseTo(before.nodeAlpha[alb], 6);
    });

    it('T-A7 : tracé segment par segment (150 ms/segment, ≤ 0,9 s), puis particules ×2 pendant 2 s, sans saut', () => {
      expect(f(at).edgeSweep[e1]).toBe(0);
      expect(f(at + 100).edgeSweep[e1]).toBeGreaterThan(0);
      expect(f(at + NARRATION.sweepMaxMs).edgeSweep[e1]).toBe(1);
      // Parcours : continu à chaque instant, pente 2 pendant la fenêtre accélérée, puis 1.
      let last = f(at).edgeTravel[e1];
      for (let now = at + 50; now <= at + 4000; now += 50) {
        const travel = f(now).edgeTravel[e1];
        expect(travel - last).toBeGreaterThan(0);
        expect(travel - last).toBeLessThanOrEqual(0.1 + 1e-6);
        last = travel;
      }
      const sweep = Math.min(NARRATION.sweepMaxMs, NARRATION.segmentMs * 4);
      const slope = (f(at + sweep + 1100).edgeTravel[e1] - f(at + sweep + 100).edgeTravel[e1]);
      expect(slope).toBeCloseTo(2, 3);
      const after2s = (f(at + sweep + 3100).edgeTravel[e1] - f(at + sweep + 2100).edgeTravel[e1]);
      expect(after2s).toBeCloseTo(1, 3);
    });
  });
});
