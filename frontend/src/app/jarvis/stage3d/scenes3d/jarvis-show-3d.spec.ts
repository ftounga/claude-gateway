import { ShowFilter, parseShowLine, resolveShow } from '../../scenes/jarvis-show';
import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { PANEL_FLY_MS, arrivalDelay, flyLook } from '../jarvis-panels';
import { IDLE_SCREEN, universeView } from '../jarvis-stage-director';
import { SCENES_3D, SGEN_3D_IDS } from './index';
import { BANC_SCENE_VIEWS, sceneOf } from './jarvis-scene3d.fixture';
import { SceneContext } from './jarvis-scene3d.model';
import { sceneMessage } from './jarvis-scene-message';
import { EMPTY_SESSION } from './jarvis-session-memory';
import { directShow } from './jarvis-show-3d';

const FILTERS: ShowFilter[] = ['clients', 'connectes', 'deconnectes', 'chantiers-bloques', 'etapes-en-cours',
  'a-envoyer'];

function ctx(extra: Partial<SceneContext> = {}): SceneContext {
  return { universe: testUniverse(), subjectId: 's-forge',
    nodes: new Set(['h-cagip', 's-forge', 's-forge#3', 'h-cagip:zahi']), pulses: 1, narrow: false,
    session: EMPTY_SESSION, spokenTickets: [], stepsDone: {}, ...extra };
}

describe('F-236 / SF-236-11 — S1 enveloppe et T-P3', () => {
  it('zoom sur le destinataire ; destinataire puis objet, jamais le corps ; teinte d’attente', () => {
    const plan = sceneMessage(BANC_SCENE_VIEWS['message'], ctx());
    expect(plan.focus).toBe('h-cagip:zahi');
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ anchor: 'h-cagip:zahi', tone: 'wait',
      kind: 'envelope' }));
    expect(plan.panels[0].lines.map((l) => l.text)).toEqual(['À : Zahi', 'Objet : Bascule DNS samedi']);
    expect(plan.panels[0].fly).toBeUndefined();
  });

  it('fin du bloc : l’enveloppe vole vers « À envoyer » ; un courriel pour soi ne part pas au compteur', () => {
    expect(sceneMessage(BANC_SCENE_VIEWS['envoi'], ctx()).panels[0].fly).toBe('to-send');
    const forMe = sceneOf('S1', 'email_me', 'Courriel pour vous', { entity: null },
      { envelope: 'mail', forMe: true, finished: true, subject: 'Récap' });
    const plan = sceneMessage(forMe, ctx());
    expect(plan.panels[0].fly).toBeUndefined();
    expect(plan.panels[0].lines[0].text).toBe('Pour vous');
  });

  it('jamais de corps : seules les lignes destinataire / objet / preuves existent', () => {
    const scene = sceneOf('S1', 'block-message', 'Message', {}, { envelope: 'message', to: 'Zahi', proof: true });
    expect(sceneMessage(scene, ctx()).panels[0].lines.map((l) => l.text)).toEqual(['À : Zahi', 'Preuves jointes']);
  });

  it('T-P3 : 1,1 s, réduction 1 → 0,25, arc ; le compteur ne prend +1 qu’à l’arrivée (en 3D seulement)', () => {
    expect(flyLook(0)).toEqual(jasmine.objectContaining({ k: 0, scale: 1, opacity: 1 }));
    const end = flyLook(PANEL_FLY_MS);
    expect(end.scale).toBeCloseTo(0.25, 5);
    expect(end.opacity).toBe(0);
    expect(flyLook(PANEL_FLY_MS / 2).lift).toBeCloseTo(1, 1);
    expect(arrivalDelay(2, 3, true)).toBe(PANEL_FLY_MS);
    expect(arrivalDelay(2, 3, false)).toBe(0);
    expect(arrivalDelay(3, 2, true)).toBe(0);
  });

  it('S1 a son rendu dédié', () => {
    expect(Object.hasOwn(SCENES_3D, 'S1')).toBeTrue();
    expect(SGEN_3D_IDS.has('S1')).toBeFalse();
  });
});

describe('F-236 / SF-236-11 — « Montrer » en 3D (S20, T-M)', () => {
  it('chaque filtre montre EXACTEMENT les éléments de resolveShow, dans le même ordre', () => {
    for (const filter of FILTERS) {
      const focus = resolveShow(testUniverse(), { filter, client: null });
      const plan = directShow(focus);
      expect(plan?.keys).withContext(filter).toEqual(focus!.items.map((i) => i.node.key));
      expect(Object.keys(plan!.tones).sort()).withContext(filter).toEqual([...plan!.keys].sort());
    }
  });

  it('filtre inconnu ⇒ rien n’est montré', () => {
    const request = parseShowLine('🖥 montrer: licornes');
    expect(request).toBeNull();
    expect(directShow(resolveShow(testUniverse(), request))).toBeNull();
  });

  it('teintes : connectés en vert, déconnectés éteints, bloqués en attente ; aucun rouge (réservé aux alertes)', () => {
    const tones = (filter: ShowFilter) => directShow(resolveShow(testUniverse(), { filter, client: null }))!.tones;
    expect(tones('connectes')['h-cagip']).toBe('green');
    expect(tones('deconnectes')['h-eden']).toBe('off');
    expect(tones('chantiers-bloques')['s-forge#3']).toBe('wait');
    expect(tones('clients')['h-eden']).toBe('off');
    for (const filter of FILTERS) {
      expect(Object.values(tones(filter))).withContext(filter).not.toContain('alert');
    }
  });

  it('étapes en cours / à envoyer : un panneau par élément, ancré à son nœud ; le titre au cœur', () => {
    const steps = directShow(resolveShow(testUniverse(), { filter: 'etapes-en-cours', client: null }))!;
    expect(steps.panels[0]).toEqual(jasmine.objectContaining({ anchor: CORE_KEY, kind: 'show' }));
    expect(steps.panels[1]).toEqual(jasmine.objectContaining({ anchor: 's-forge#3', kind: 'chantier' }));
    expect(steps.panels[1].lines[0].text).toContain('étape 3/5');
    const mail = directShow(resolveShow(testUniverse(), { filter: 'a-envoyer', client: null }))!;
    expect(mail.panels.slice(1).every((p) => p.kind === 'envelope' && p.tone === 'wait')).toBeTrue();
  });

  it('les éléments montrés sont allumés, le reste en latent ; les chantiers montrés déploient leur chapelet', () => {
    const focus = resolveShow(testUniverse(), { filter: 'etapes-en-cours', client: null });
    const s = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-doc', showFocus: focus,
      shown: focus!.items.map((i) => i.node.key) };
    const view = universeView(s, false, false);
    expect(view.graph.nodes.get('s-forge#3')).toBe('lit');
    expect(view.layout.nodes.some((n) => n.key === 's-forge#3/1')).toBeTrue();
    expect(view.focus).toBeNull(); // T-M : dézoom (vue d'ensemble)
  });
});
