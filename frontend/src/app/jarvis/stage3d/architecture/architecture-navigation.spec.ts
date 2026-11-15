import { TestBed } from '@angular/core/testing';

import { defaultArchNav } from '../../pieces/jarvis-piece-arch';
import { JarvisPieceClient } from '../../pieces/jarvis-piece-client';
import { JarvisPieceStore } from '../../pieces/jarvis-piece-store';
import { Piece } from '../../pieces/jarvis-piece.models';
import { archStateOf, servicePanel } from '../../pieces/jarvis-piece-architecture.component';
import { TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import { ArchLayout, layoutArchitecture } from './architecture-layout-3d';
import {
  ArchState, ArchitectureStageDirector, DEFAULT_ARCH_STATE, NAV_MOVES, blendCamera, moveFor, moveProgress, pickAt,
  springProgress,
} from './architecture-stage-director';
import { ArchitectureTargets } from './architecture-targets';

const VIEW = { width: 1920, height: 1080 };

function layout(graph = TROIS_TIERS_GRAPH): ArchLayout {
  return layoutArchitecture(graph) as ArchLayout;
}

describe('Architecture — cibles dites (F-238 / SF-238-10)', () => {
  const targets = new ArchitectureTargets(layout());
  const arch = defaultArchNav();

  it('id, libellé, sigle : un seul élément ⇒ résolu', () => {
    expect(targets.resolve({ kind: 'focus', target: 'rdsa' }, arch)).toEqual({ op: { kind: 'focus',
      ref: { id: 'rdsa', label: 'RDS primaire' } } });
    expect(targets.resolve({ kind: 'focus', target: 'RDS primaire' }, arch)).toEqual({ op: { kind: 'focus',
      ref: { id: 'rdsa', label: 'RDS primaire' } } });
    expect(targets.resolve({ kind: 'focus', target: "l'ALB" }, arch)).toEqual({ op: { kind: 'focus',
      ref: { id: 'alb', label: 'ALB' } } });
    expect(targets.resolve({ kind: 'focus', target: 'le VPC' }, arch)).toEqual({ op: { kind: 'focus',
      ref: { id: 'vpc', label: 'VPC 10.0.0.0/16' } } });
  });

  it('« la base » avec deux bases ⇒ la question, jamais un choix deviné', () => {
    const r = targets.resolve({ kind: 'focus', target: 'la base' }, arch);
    expect('refusal' in r && r.refusal).toBe('AMBIGUOUS');
    expect('refusal' in r && r.detail).toBe('Il y en a deux : RDS primaire et RDS secours. Lequel ?');
    expect(targets.resolve({ kind: 'focus', target: 'le mainframe' }, arch)).toEqual({ refusal: 'NOT_FOUND' });
  });

  it('replier : la zone nommée, la zone du service nommé, ou « tout » = les zones qui portent des services', () => {
    expect(targets.resolve({ kind: 'fold', target: 'zone B' }, arch)).toEqual({ op: { kind: 'fold',
      refs: [{ id: 'azb', label: 'Availability Zone B' }] } });
    expect(targets.resolve({ kind: 'fold', target: 'Web A' }, arch)).toEqual({ op: { kind: 'fold',
      refs: [{ id: 'priva', label: 'Sous-réseau privé A' }] } });
    const all = targets.resolve({ kind: 'fold', target: '' }, arch);
    expect('op' in all && all.op.kind === 'fold' && all.op.refs.map((r) => r.id).sort())
      .toEqual(['cloud', 'priva', 'privb', 'puba', 'pubb', 'region', 'vpc']);
  });

  it('flux : l’entrée, le sortant du focus, deux services reliés', () => {
    expect(targets.resolve({ kind: 'flow', target: 'entrée' }, arch)).toEqual({ op: { kind: 'flow',
      edges: ['e0', 'e1'], label: 'entrée' } });
    const focused = { ...arch, focus: { id: 'alb', label: 'ALB' } };
    expect(targets.resolve({ kind: 'flow', target: 'sortant' }, focused)).toEqual({ op: { kind: 'flow',
      edges: ['e2', 'e3'], label: 'sortant de ALB' } });
    const path = targets.resolve({ kind: 'flow', target: 'de l’ALB vers RDS primaire' }, arch);
    expect('op' in path && path.op.kind === 'flow' && path.op.edges).toEqual(['e2', 'e4']);
  });
});

describe('Architecture — transitions de navigation (F-238 / SF-238-10)', () => {
  const l = layout();
  const d = new ArchitectureStageDirector(l);
  const base: ArchState = { ...DEFAULT_ARCH_STATE };

  it('chaque changement a sa transition nommée (T-A3 → T-A6, T-A9) ; la lumière seule n’en a pas', () => {
    expect(moveFor(base, { ...base, view: 'dessus' })).toEqual({ ms: NAV_MOVES.view, curve: 'ressort' });
    expect(moveFor(base, { ...base, focus: 'rdsa' })).toEqual({ ms: NAV_MOVES.focus, curve: 'ressort' });
    expect(moveFor(base, { ...base, exploded: true })).toEqual({ ms: NAV_MOVES.explode, curve: 'glisse' });
    expect(moveFor(base, { ...base, folded: ['azb'] })).toEqual({ ms: NAV_MOVES.fold, curve: 'entree' });
    expect(moveFor({ ...base, folded: ['azb'] }, base)).toEqual({ ms: NAV_MOVES.unfold, curve: 'sortie' });
    expect(moveFor(base, { ...base, lit: ['alb'] }).ms).toBe(0);
  });

  it('replier puis déplier ⇒ plan identique', () => {
    const before = d.plan(base, VIEW);
    const folded = d.plan({ ...base, folded: ['azb'] }, VIEW);
    expect(Array.from(folded.nodeFold)).not.toEqual(Array.from(before.nodeFold));
    const after = d.plan({ ...base, folded: [] }, VIEW);
    expect(after.camera).toEqual(before.camera);
    for (const key of ['nodeFold', 'plateHidden', 'plateCapsule', 'nodeAlpha', 'edgeAlpha', 'plateLine'] as const) {
      expect(Array.from(after[key])).withContext(key).toEqual(Array.from(before[key]));
    }
  });

  it('une zone repliée rentre ses services en capsule au centre du plateau (« ⌬ n services »)', () => {
    const plan = d.plan({ ...base, folded: ['azb'] }, VIEW);
    const azb = l.plateById.get('azb') as number;
    expect(plan.plateCapsule[azb]).toBe(1);
    expect(plan.capsuleText[azb]).toBe('⌬ 3 services');
    expect(plan.plateHidden[l.plateById.get('privb') as number]).toBe(1);
    expect(plan.nodeFold[l.nodeById.get('rdsb') as number]).toBe(1);
    expect(plan.nodeFold[l.nodeById.get('rdsa') as number]).toBe(0);
    const f = d.frame(plan, 'repos', 0, d.newFrame());
    const c = l.plates[azb];
    expect(f.nodeX[l.nodeById.get('rdsb') as number]).toBeCloseTo(c.x0 + c.w / 2, 6);
  });

  it('aucun saut : à 16 ms d’écart, caméra, altitudes et replis avancent de moins de 30 % de l’écart (règle F-236)', () => {
    const cases: [ArchState, ArchState][] = [
      [base, { ...base, exploded: true }], [base, { ...base, folded: ['azb', 'priva'] }],
      [base, { ...base, view: 'dessus' }], [base, { ...base, focus: 'rdsa' }], [{ ...base, folded: ['azb'] }, base],
    ];
    for (const [a, b] of cases) {
      const from = d.plan(a, VIEW);
      const to = d.plan(b, VIEW);
      const out = d.newFrame();
      d.frame(from, 'repos', 0, out, 0);
      const { ms, curve } = moveFor(a, b);
      const move = d.startMove(out, from.camera, 1000, ms, curve);
      const gap = (x: number, y: number): number => Math.abs(x - y);
      const altGap = Math.max(1e-6, ...Array.from(out.plateAlt, (v, i) => gap(v, l.plates[i].level * to.step * to.lift)));
      const camGap = Math.max(1e-6, gap(from.camera.distance, to.camera.distance), gap(from.camera.elevation,
        to.camera.elevation) * 10);
      let prevAlt = Array.from(out.plateAlt);
      let prevFold = Array.from(out.nodeFold);
      let prevCam = blendCamera(move.fromCamera, to.camera, 0);
      for (let t = 1000; t <= 1000 + ms + 32; t += 16) {
        d.frame(to, 'repos', t, out, t, null, move);
        const cam = blendCamera(move.fromCamera, to.camera, moveProgress(move, t));
        const da = Math.max(...Array.from(out.plateAlt, (v, i) => gap(v, prevAlt[i])));
        const df = Math.max(...Array.from(out.nodeFold, (v, i) => gap(v, prevFold[i])));
        const dc = Math.max(gap(cam.distance, prevCam.distance), gap(cam.elevation, prevCam.elevation) * 10);
        expect(da / altGap).withContext(`${JSON.stringify(b)} altitudes à ${t}`).toBeLessThan(0.3);
        expect(df).withContext(`replis à ${t}`).toBeLessThan(0.3);
        expect(dc / camGap).withContext(`caméra à ${t}`).toBeLessThan(0.3);
        prevAlt = Array.from(out.plateAlt);
        prevFold = Array.from(out.nodeFold);
        prevCam = cam;
      }
      // À la fin : l'état du plan, exactement.
      expect(Array.from(out.plateAlt)).toEqual(l.plates.map((p) => Math.fround(p.level * to.step * to.lift)));
      expect(Array.from(out.nodeFold)).toEqual(Array.from(to.nodeFold));
    }
  });

  it('le ressort de caméra arrive sans dépasser ; une transition interrompue repart de l’image affichée', () => {
    let last = 0;
    for (let u = 0; u <= 1; u += 0.02) {
      const v = springProgress(u, NAV_MOVES.view);
      expect(v).toBeGreaterThanOrEqual(last);
      expect(v).toBeLessThanOrEqual(1);
      last = v;
    }
    expect(springProgress(1, NAV_MOVES.view)).toBe(1);
    const from = d.plan(base, VIEW);
    const to = d.plan({ ...base, exploded: true }, VIEW);
    const out = d.newFrame();
    d.frame(from, 'repos', 0, out);
    const move = d.startMove(out, from.camera, 0, NAV_MOVES.explode, 'glisse');
    d.frame(to, 'repos', 600, out, 600, null, move);
    const mid = Array.from(out.plateAlt);
    const back = d.startMove(out, from.camera, 600, NAV_MOVES.explode, 'glisse');
    d.frame(from, 'repos', 600, out, 600, null, back);
    expect(Array.from(out.plateAlt)).toEqual(mid);
  });

  it('au-delà de 40 services, les AZ jumelles fusionnent dans la première (« × 3 »)', () => {
    const big = layout(sixtyNodesGraph());
    const director = new ArchitectureStageDirector(big);
    const plan = director.plan(base, VIEW);
    const [a, b, c] = ['az0', 'az1', 'az2'].map((id) => big.plateById.get(id) as number);
    expect(plan.plateBadge[a]).toBe('× 3');
    expect(plan.plateCapsule[b]).toBe(1);
    expect(plan.plateCapsule[c]).toBe(1);
    expect(plan.capsuleText[b]).toBe('= Availability Zone A');
    expect(plan.nodeFold.filter((v) => v === 1).length).toBe(40);
    // Un focus dans une jumelle la déplie.
    const focused = director.plan({ ...base, focus: 'n100' }, VIEW);
    expect(focused.plateCapsule[b]).toBe(0);
    // Sous le seuil : rien ne fusionne.
    expect(d.plan(base, VIEW).plateBadge.every((x) => x === '')).toBeTrue();
  });

  it('le focus cadre ses voisins (T-A6) : la caméra se rapproche', () => {
    const all = d.plan(base, VIEW).camera;
    const focus = d.plan({ ...base, focus: 'rdsa' }, VIEW).camera;
    expect(focus.distance).toBeLessThan(all.distance);
  });

  it('toucher : le service sous le doigt, sinon la zone la plus précise', () => {
    const plan = d.plan({ ...base, view: 'dessus' }, VIEW);
    const out = d.frame(plan, 'repos', 0, d.newFrame());
    const rds = l.nodes[l.nodeById.get('rdsa') as number];
    const { x, y } = (() => {
      const g = TROIS_TIERS_GRAPH.nodes.find((n) => n.id === 'rdsa')!;
      const scale = Math.min(VIEW.width * 0.9 / TROIS_TIERS_GRAPH.bounds.w, VIEW.height * 0.9 / TROIS_TIERS_GRAPH.bounds.h);
      const dx = (VIEW.width - TROIS_TIERS_GRAPH.bounds.w * scale) / 2;
      const dy = (VIEW.height - TROIS_TIERS_GRAPH.bounds.h * scale) / 2;
      return { x: dx + (g.box.x + g.box.w / 2) * scale, y: dy + (g.box.y + 32) * scale };
    })();
    expect(rds.id).toBe('rdsa');
    expect(pickAt(l, out, plan.camera, VIEW, x, y)).toEqual({ kind: 'node', id: 'rdsa' });
    const zone = TROIS_TIERS_GRAPH.zones.find((z) => z.id === 'pubb')!;
    const scale = Math.min(VIEW.width * 0.9 / TROIS_TIERS_GRAPH.bounds.w, VIEW.height * 0.9 / TROIS_TIERS_GRAPH.bounds.h);
    const dx = (VIEW.width - TROIS_TIERS_GRAPH.bounds.w * scale) / 2;
    const dy = (VIEW.height - TROIS_TIERS_GRAPH.bounds.h * scale) / 2;
    expect(pickAt(l, out, plan.camera, VIEW, dx + (zone.box.x + 12) * scale, dy + (zone.box.y + zone.box.h - 12) * scale))
      .toEqual({ kind: 'plate', id: 'pubb' });
    expect(pickAt(l, out, plan.camera, VIEW, 2, 2)).toBeNull();
  });
});

describe('Architecture — la visionneuse dans la galerie (F-238 / SF-238-10)', () => {
  const ARCH: Piece = { id: 'diag:1', category: 'PRODUCTION', kind: 'ARCHITECTURE', title: '3-tiers' };
  let store: JarvisPieceStore;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: JarvisPieceClient, useValue: {} }] });
    store = TestBed.inject(JarvisPieceStore);
    store.show({ subjectId: 's', label: 'Pièces' }, [ARCH]);
    store.dispatch({ type: 'select', ref: 1 });
  });

  it('une cible dite se résout contre le graphe lié ; ambiguë ⇒ la question est rendue', () => {
    const targets = new ArchitectureTargets(layout());
    const unbind = store.bindArchitecture({ pieceId: 'diag:1', resolve: (r, a) => targets.resolve(r, a) });
    expect(store.dispatch({ type: 'arch', request: { kind: 'focus', target: 'RDS primaire' } }, 'ai').ok).toBeTrue();
    expect(store.nav().arch?.focus).toEqual({ id: 'rdsa', label: 'RDS primaire' });
    const ambiguous = store.dispatch({ type: 'arch', request: { kind: 'focus', target: 'la base' } }, 'voice');
    expect(ambiguous).toEqual({ ok: false, reason: 'AMBIGUOUS', detail: 'Il y en a deux : RDS primaire et RDS secours. Lequel ?' });
    unbind();
  });

  it('un premier cadrage arrivé avant le graphe attend la visionneuse (≤ 10 s)', () => {
    expect(store.dispatch({ type: 'arch', request: { kind: 'flow', target: 'entrée' } }, 'ai', 1000).ok).toBeTrue();
    expect(store.nav().arch?.flow).toBeNull();
    const targets = new ArchitectureTargets(layout());
    store.bindArchitecture({ pieceId: 'diag:1', resolve: (r, a) => targets.resolve(r, a) }, 3000);
    expect(store.nav().arch?.flow).toEqual({ edges: ['e0', 'e1'], label: 'entrée' });
  });

  it('sans graphe (repli 2D), « zoome sur … » cadre la partie nommée du SVG tout de suite', () => {
    expect(store.dispatch({ type: 'arch', request: { kind: 'focus', target: 'le VPC' } }, 'ai').ok).toBeTrue();
    expect(store.nav().target).toBe('le VPC');
    expect(store.nav().zoom).toBe(2);
  });

  it('l’état de scène et la fiche du service suivent la navigation', () => {
    store.dispatch({ type: 'arch', request: { kind: 'explode', on: true } });
    store.dispatch({ type: 'zoom', step: '+' });
    const state = archStateOf(store.nav(), true);
    expect(state.exploded).toBeTrue();
    expect(state.zoom).toBe(2);
    expect(state.phone).toBeTrue();
    expect(servicePanel(layout(), 'alb')).toEqual({ label: 'ALB', type: 'ALB', zone: 'VPC 10.0.0.0/16',
      inbound: ['Utilisateurs'], outbound: ['Web A', 'Web B'] });
    expect(servicePanel(layout(), null)).toBeNull();
  });
});
