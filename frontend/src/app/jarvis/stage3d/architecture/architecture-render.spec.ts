import { TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import { ArchLayout, layoutArchitecture } from './architecture-layout-3d';
import { ArchRenderContext, archRender, maskGraph, maskSensitive } from './architecture-render';
import {
  ArchitectureStageDirector, DEFAULT_ARCH_STATE, PHONE_LABELS, projectToScreen,
} from './architecture-stage-director';

const OK: ArchRenderContext = { universe3d: true, sober: false, reducedMotion: false, graph: true, webglFailed: false,
  perfFallback: false, flat: false };

describe('Architecture — repli 2D, téléphone, présentation (F-238 / SF-238-11)', () => {
  it('table de vérité : chaque cause ⇒ le SVG cadré, la première l’emporte', () => {
    expect(archRender(OK)).toEqual({ mode: '3d' });
    expect(archRender({ ...OK, graph: null })).toEqual({ mode: '3d' }); // lecture en cours : on attend
    const cases: [Partial<ArchRenderContext>, string][] = [
      [{ universe3d: false }, 'universe_2d'], [{ sober: true }, 'sober'], [{ reducedMotion: true }, 'reduced_motion'],
      [{ graph: false }, 'graph'], [{ webglFailed: true }, 'webgl'], [{ perfFallback: true }, 'perf'],
      [{ flat: true }, 'manual'],
    ];
    for (const [patch, reason] of cases) {
      expect(archRender({ ...OK, ...patch })).withContext(reason).toEqual({ mode: '2d', reason } as never);
    }
    expect(archRender({ ...OK, universe3d: false, flat: true, graph: false })).toEqual({ mode: '2d', reason: 'universe_2d' });
  });

  it('présentation : CIDR, comptes et ARN masqués, le reste du libellé gardé', () => {
    expect(maskSensitive('VPC 10.0.0.0/16')).toBe('VPC 10.0.•.•/16');
    expect(maskSensitive('Compte 123456789012 (prod)')).toBe('Compte •••• 9012 (prod)');
    expect(maskSensitive('Rôle arn:aws:iam::123456789012:role/admin')).toBe('Rôle arn:aws:••• •••• 9012');
    expect(maskSensitive('RDS primaire')).toBe('RDS primaire');
    const masked = maskGraph({ ...TROIS_TIERS_GRAPH, edges: TROIS_TIERS_GRAPH.edges.map((e, i) => i === 0
      ? { ...e, label: 'vers 172.16.4.20' } : e) });
    const layout = layoutArchitecture(masked) as ArchLayout;
    const all = JSON.stringify([masked.title, masked.zones.map((z) => z.label), masked.nodes.map((n) => n.label),
      masked.edges.map((e) => e.label), layout.plates.map((p) => p.label), layout.nodes.map((n) => n.label),
      layout.edges.map((e) => e.label)]);
    expect(all).not.toMatch(/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/);
    expect(all).not.toMatch(/\b\d{12}\b/);
    expect(layout.plates.find((p) => p.id === 'vpc')?.label).toBe('VPC 10.0.•.•/16');
  });

  it('téléphone : le schéma prend l’orientation qui le rend le plus grand', () => {
    const l = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    const d = new ArchitectureStageDirector(l);
    const phone = { width: 390, height: 844 };
    const width = (camera: ReturnType<typeof d.plan>['camera']): number => {
      const xs = [[-l.width / 2, -l.depth / 2], [l.width / 2, -l.depth / 2], [-l.width / 2, l.depth / 2],
        [l.width / 2, l.depth / 2]].map(([x, z]) => projectToScreen(camera, phone, x, 0, z).x);
      return Math.max(...xs) - Math.min(...xs);
    };
    const plan = d.plan({ ...DEFAULT_ARCH_STATE, phone: true }, phone);
    expect(width(plan.camera) / phone.width).toBeGreaterThanOrEqual(0.6);
  });

  it('téléphone : 16 étiquettes au plus, les zones qui structurent le schéma d’abord', () => {
    const l = layoutArchitecture(TROIS_TIERS_GRAPH) as ArchLayout;
    const plan = new ArchitectureStageDirector(l).plan({ ...DEFAULT_ARCH_STATE, phone: true }, { width: 390, height: 844 });
    const zones = l.plates.filter((p) => plan.plateLabel[p.index]).map((p) => p.kind);
    expect(zones).toEqual(jasmine.arrayContaining(['az', 'az', 'subnet_public', 'subnet_public', 'subnet_private',
      'subnet_private', 'vpc']));
    const count = plan.plateLabel.filter(Boolean).length + plan.nodeLabel.filter(Boolean).length;
    expect(count).toBeLessThanOrEqual(PHONE_LABELS);
    const big = layoutArchitecture(sixtyNodesGraph()) as ArchLayout;
    const p60 = new ArchitectureStageDirector(big).plan({ ...DEFAULT_ARCH_STATE, phone: true }, { width: 390, height: 844 });
    expect(p60.plateLabel.filter(Boolean).length + p60.nodeLabel.filter(Boolean).length).toBeLessThanOrEqual(PHONE_LABELS);
  });
});
