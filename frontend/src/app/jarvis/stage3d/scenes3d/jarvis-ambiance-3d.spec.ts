import { ShowFilter, resolveShow } from '../../scenes/jarvis-show';
import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { FOLDED } from '../jarvis-brains';
import { IDLE_SCREEN, StagePlan, planTones, stageScene, universeView } from '../jarvis-stage-director';
import { AmbientContext, AmbientPlan3d, directAmbient } from './jarvis-ambiance-3d';
import { BANC_AMBIENTS, BANC_SCENE_VIEWS, QUIET } from './jarvis-scene3d.fixture';
import { directShow } from './jarvis-show-3d';

function ctx(extra: Partial<AmbientContext> = {}): AmbientContext {
  const view = universeView({ ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge' }, false, false);
  return { universe: testUniverse(), nodes: new Set(view.layout.nodes.map((n) => n.key)), busy: false, admin: false,
    costFresh: true, seq: 1, ...extra };
}

function plan(ambient: AmbientPlan3d | null): StagePlan {
  return { mood: 'repos', wakes: 0, brains: FOLDED, universe: universeView(IDLE_SCREEN, false, false), scene: null,
    ambient };
}

describe('F-236 / SF-236-13 — ambiance (A1-A5)', () => {
  it('A1 poste muet : client rouge qui grésille, « Poste muet »', () => {
    const a = directAmbient(BANC_AMBIENTS['poste-muet'], ctx())!;
    expect(a.tones['h-cagip']).toBe('alert');
    expect(a.flicker).toEqual(['h-cagip']);
    expect(a.panels[0]).toEqual(jasmine.objectContaining({ title: 'Poste muet', tone: 'alert', anchor: 'h-cagip' }));
  });

  it('A2 terminaux vivants : les lunes respirent, une impulsion part vers leur client', () => {
    const a = directAmbient(BANC_AMBIENTS['vivants'], ctx())!;
    expect(a.breathe).toEqual(['s-forge', 's-doc', 's-web']);
    expect(a.effects).toContain(jasmine.objectContaining({ kind: 'flow', from: 's-forge', to: 'h-cagip' }));
  });

  it('A3 cloche : le sujet appelle (teinte d’attente) et lance une onde, une par appel', () => {
    const a = directAmbient(BANC_AMBIENTS['cloche'], ctx({ seq: 4 }))!;
    expect(a.tones['s-doc']).toBe('wait');
    expect(a.effects).toContain({ kind: 'pulse', anchor: 's-doc', tone: 'wait', seq: 4 });
  });

  it('A4 coût : JAMAIS pour un non-administrateur ; administrateur : 5 s seulement', () => {
    expect(directAmbient(BANC_AMBIENTS['cout'], ctx({ admin: false }))).toBeNull();
    const admin = directAmbient(BANC_AMBIENTS['cout'], ctx({ admin: true }))!;
    expect(admin.panels[0]).toEqual(jasmine.objectContaining({ kind: 'cost', anchor: CORE_KEY }));
    expect(admin.panels[0].lines[0].text).toBe('0,42 €');
    expect(directAmbient(BANC_AMBIENTS['cout'], ctx({ admin: true, costFresh: false }))).toBeNull();
  });

  it('A5 la veille sonne : zoom sur le client, onde vers le cœur, cadran relié (L-veille), rouge, devant tout', () => {
    const a = directAmbient(BANC_AMBIENTS['veille'], ctx())!;
    expect(a.focus).toBe('h-cagip');
    expect(a.effects).toContain(jasmine.objectContaining({ kind: 'flow', from: 'h-cagip', to: CORE_KEY,
      tone: 'alert' }));
    expect(a.effects).toContain({ kind: 'dial', anchor: 'h-cagip', tone: 'alert' });
    expect(a.panels[0]).toEqual(jasmine.objectContaining({ tone: 'alert', front: true }));
  });

  it('au repos seulement : pendant un tour ou une scène, seule A5 se montre', () => {
    const all = { ...QUIET, hostDown: { clientKey: 'h-cagip', name: 'CAGIP' }, liveSubjects: new Set(['s-doc']),
      calling: { subjectId: 's-doc', label: 'Doc archi' }, cost: '1 €',
      alert: { clientKey: 'h-eden', label: 'Veille' } };
    const busy = directAmbient(all, ctx({ busy: true, admin: true }))!;
    expect(busy.panels.map((p) => p.id)).toEqual(['ambient:alert']);
    expect(busy.breathe).toEqual([]);
    expect(directAmbient({ ...all, alert: null }, ctx({ busy: true, admin: true }))).toBeNull();
    expect(directAmbient(QUIET, ctx())).toBeNull();
  });
});

describe('F-236 / SF-236-13 — N13 : le rouge est réservé aux alertes', () => {
  it('aucune scène, aucun « Montrer », aucune ambiance hors alerte n’emploie le rouge', () => {
    for (const [name, view] of Object.entries(BANC_SCENE_VIEWS)) {
      const s = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', scene: view };
      const p = { ...plan(null), scene: stageScene(s, universeView(s, false, false), false) };
      expect(planTones(p).filter((t) => t.tone === 'alert')).withContext(name).toEqual([]);
    }
    const filters: ShowFilter[] = ['clients', 'connectes', 'deconnectes', 'chantiers-bloques', 'etapes-en-cours',
      'a-envoyer'];
    for (const filter of filters) {
      const p = { ...plan(null), show: directShow(resolveShow(testUniverse(), { filter, client: null })) };
      expect(planTones(p).some((t) => t.tone === 'alert')).withContext(filter).toBeFalse();
    }
    for (const name of ['vivants', 'cloche', 'cout']) {
      const p = plan(directAmbient(BANC_AMBIENTS[name], ctx({ admin: true })));
      expect(planTones(p).some((t) => t.tone === 'alert')).withContext(name).toBeFalse();
    }
  });

  it('A1 et A5 emploient le rouge, et seulement l’ambiance le porte', () => {
    for (const name of ['poste-muet', 'veille']) {
      const reds = planTones(plan(directAmbient(BANC_AMBIENTS[name], ctx()))).filter((t) => t.tone === 'alert');
      expect(reds.length).withContext(name).toBeGreaterThan(0);
      expect(reds.every((t) => t.from === 'ambient')).withContext(name).toBeTrue();
    }
  });
});
