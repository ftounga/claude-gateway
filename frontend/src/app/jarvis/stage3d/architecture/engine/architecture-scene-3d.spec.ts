import { TROIS_TIERS_GRAPH, sixtyNodesGraph } from '../architecture-graph.fixture';
import { neutralIconSource } from '../architecture-icons';
import { ArchLayout, layoutArchitecture } from '../architecture-layout-3d';
import { DEFAULT_ARCH_STATE } from '../architecture-stage-director';
import { seededRandom } from '../../jarvis-banc';
import { ArchitectureSceneGraph, PARTICLES_PC, PARTICLES_PHONE } from './architecture-scene-3d';

/** La scène construite sans rendu (Karma, sans WebGL) : on compte ce qui serait dessiné. */
function build(layout: ArchLayout, phone = false, labelHost: HTMLElement | null = null): ArchitectureSceneGraph {
  return new ArchitectureSceneGraph({
    layout, icons: neutralIconSource(), level: phone ? 'medium' : 'high', phone, pixelRatio: 1,
    random: seededRandom(238), labelHost,
  });
}

describe('F-238 / SF-238-09 — la scène 3D (sans rendu)', () => {
  it('60 services : ≤ 20 appels de dessin (objets dessinables visibles, hors post-traitement)', () => {
    const layout = layoutArchitecture(sixtyNodesGraph()) as ArchLayout;
    expect(layout.nodes.length).toBe(60);
    const scene = build(layout);
    const plan = scene.director.plan(DEFAULT_ARCH_STATE, { width: 1920, height: 1080 });
    scene.update(plan, 'repos', 0, 1, 16 / 9);
    expect(scene.drawables()).toBeLessThanOrEqual(20);
    scene.dispose();
  });

  it('les particules courent sur toutes les liaisons, bornées par le pool (PC 2 000, téléphone 400)', () => {
    const layout = layoutArchitecture(sixtyNodesGraph()) as ArchLayout;
    const pc = build(layout);
    pc.update(pc.director.plan(DEFAULT_ARCH_STATE, { width: 1920, height: 1080 }), 'repos', 0, 2, 16 / 9);
    expect(pc.particleCount).toBeGreaterThan(0);
    expect(pc.particleCount).toBeLessThanOrEqual(PARTICLES_PC);
    const phone = build(layout, true);
    phone.update(phone.director.plan({ ...DEFAULT_ARCH_STATE, phone: true }, { width: 390, height: 844 }), 'repos',
      0, 2, 390 / 844);
    expect(phone.particleCount).toBeLessThanOrEqual(PARTICLES_PHONE);
    pc.dispose();
    phone.dispose();
  });

  it('aucune particule avant que le filament soit tracé (T-A1)', () => {
    const scene = build(layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout);
    const plan = scene.director.plan(DEFAULT_ARCH_STATE, { width: 1920, height: 1080 });
    scene.update(plan, 'entree', 1500, 1.5, 16 / 9);
    expect(scene.particleCount).toBe(0);
    scene.update(plan, 'entree', 2200, 2.2, 16 / 9);
    expect(scene.particleCount).toBeGreaterThan(0);
    scene.dispose();
  });

  it('0 allocation par image : les mêmes tampons sont réécrits', () => {
    const scene = build(layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout);
    const plan = scene.director.plan(DEFAULT_ARCH_STATE, { width: 1920, height: 1080 });
    scene.update(plan, 'repos', 0, 1, 16 / 9);
    const particles = scene.particles;
    const path = scene.path(0);
    const frame = scene.frame;
    const alt = frame.plateAlt;
    scene.update(plan, 'repos', 16, 1.016, 16 / 9);
    expect(scene.particles).toBe(particles);
    expect(scene.path(0)).toBe(path);
    expect(scene.frame).toBe(frame);
    expect(scene.frame.plateAlt).toBe(alt);
    scene.dispose();
  });

  it('étiquettes en textContent (jamais interprétées), zones et services', () => {
    const graph = structuredClone(TROIS_TIERS_GRAPH);
    graph.nodes[0].label = '<img src=x onerror=alert(1)>';
    const host = document.createElement('div');
    const scene = build(layoutArchitecture(graph) as ArchLayout, false, host);
    const labels = scene.labelElements;
    expect(labels.length).toBe(graph.zones.length + graph.nodes.length);
    const hostile = labels.find((l) => l.textContent === '<img src=x onerror=alert(1)>') as HTMLElement;
    expect(hostile).toBeTruthy();
    expect(hostile.querySelector('img')).toBeNull();
    expect(labels.some((l) => l.textContent === 'VPC 10.0.0.0/16')).toBeTrue();
    scene.dispose();
  });
});
