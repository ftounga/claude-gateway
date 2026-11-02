import { testUniverse } from '../scenes/jarvis-universe.fixture';
import { EMPTY_LAYOUT, MAX_STEPS, layoutUniverse3d, stableHash } from './jarvis-layout-3d';
import { overviewDistance } from './jarvis-camera-rules';

const NONE = new Set<string>();

describe('F-236 / SF-236-04 — disposition 3D de l’univers (déterministe)', () => {
  it('mêmes entrées ⇒ mêmes positions (le PO apprend sa carte)', () => {
    const a = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: NONE });
    const b = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: NONE });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });

  it('hachage stable, dans [0, 1)', () => {
    expect(stableHash('h-cagip')).toBe(stableHash('h-cagip'));
    expect(stableHash('h-cagip')).not.toBe(stableHash('h-eden'));
    for (const k of ['', 'a', 'h-cagip', 'x'.repeat(200)]) {
      expect(stableHash(k)).toBeGreaterThanOrEqual(0);
      expect(stableHash(k)).toBeLessThan(1);
    }
  });

  it('univers vide ou absent ⇒ cœur seul (aucun nœud)', () => {
    expect(layoutUniverse3d(null, { subjectId: null, narrow: false, shown: NONE })).toBe(EMPTY_LAYOUT);
    expect(layoutUniverse3d({ generatedAt: '', clients: [], toSendCount: 0 },
      { subjectId: null, narrow: false, shown: NONE }).nodes).toEqual([]);
  });

  it('PC : tous les clients et tous leurs sujets ; chantier du sujet courant et son chapelet d’étapes', () => {
    const l = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: NONE });
    const kinds = (k: string) => l.nodes.filter((n) => n.kind === k).map((n) => n.key);
    expect(kinds('client').sort()).toEqual(['h-cagip', 'h-eden']);
    expect(kinds('subject').length).toBe(7);
    expect(kinds('chantier')).toEqual(['s-forge#3']);
    const steps = l.nodes.filter((n) => n.kind === 'step');
    expect(steps.length).toBe(5);
    expect(steps.filter((s) => s.done).length).toBe(2);
    expect(steps.find((s) => s.label)?.label).toBe('Basculer');
    // La personne attendue par le chantier est posée (L-attente).
    expect(l.nodes.find((n) => n.kind === 'person')).toEqual(jasmine.objectContaining({ awaited: true, label: 'Zahi' }));
    expect(l.currentClientKey).toBe('h-cagip');
  });

  it('téléphone : seuls les sujets du client courant, plus ceux cités', () => {
    const l = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: true, shown: NONE });
    expect(l.nodes.filter((n) => n.kind === 'subject').every((n) => n.clientKey === 'h-cagip')).toBeTrue();
    const cited = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: true, shown: new Set(['s-runner']) });
    expect(cited.nodes.some((n) => n.key === 's-runner')).toBeTrue();
  });

  it('téléphone : l’orbite est dressée (plus haute que large) pour occuper le portrait', () => {
    const l = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: true, shown: NONE });
    const clients = l.nodes.filter((n) => n.kind === 'client');
    expect(Math.max(...clients.map((c) => Math.abs(c.y)))).toBeGreaterThan(Math.max(...clients.map((c) => Math.abs(c.x))));
  });

  it('le chapelet est borné à 12 perles', () => {
    const u = testUniverse();
    u.clients[0].subjects[1].chantiers[0].total = 40;
    const l = layoutUniverse3d(u, { subjectId: 's-forge', narrow: false, shown: NONE });
    expect(l.nodes.filter((n) => n.kind === 'step').length).toBe(MAX_STEPS);
  });

  it('vue d’ensemble : la distance fait tenir l’univers, portrait compris (plus loin en portrait)', () => {
    const wide = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: NONE });
    const pc = overviewDistance(wide.extent, 16 / 9);
    const phone = overviewDistance(layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: true, shown: NONE })
      .extent, 390 / 844);
    expect(pc).toBeGreaterThan(9);
    expect(phone).toBeGreaterThan(9);
    // La moitié de la largeur visible à cette distance couvre l'étendue horizontale.
    const halfH = Math.atan(Math.tan((55 * Math.PI) / 360) * (16 / 9));
    expect(Math.tan(halfH) * pc).toBeGreaterThan(wide.extent.x);
    expect(overviewDistance({ x: 0, y: 0, z: 0 }, 1)).toBe(9);
  });
});
