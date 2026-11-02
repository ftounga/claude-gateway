import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { layoutUniverse3d } from '../jarvis-layout-3d';
import { IDLE_SCREEN, stageScene, universeView } from '../jarvis-stage-director';
import { boundPanels } from '../jarvis-panels';
import { SCENES_3D, SGEN_3D_IDS } from './index';
import { BANC_SCENE_VIEWS, sceneOf } from './jarvis-scene3d.fixture';
import { Effect3d, SceneContext } from './jarvis-scene3d.model';
import {
  countdownFraction, sceneAttente, sceneEtape, sceneLePoint, sceneOui, scenePointsDeRoute,
} from './jarvis-scenes-chantier';
import { EMPTY_SESSION } from './jarvis-session-memory';

function ctx(extra: Partial<SceneContext> = {}): SceneContext {
  const nodes = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false,
    shown: new Set(['s-forge#3']) }).nodes.map((n) => n.key);
  return { universe: testUniverse(), subjectId: 's-forge', nodes: new Set(nodes), pulses: 3, narrow: false,
    session: EMPTY_SESSION, spokenTickets: [], stepsDone: {}, ...extra };
}

function effect<K extends Effect3d['kind']>(effects: readonly Effect3d[], kind: K): Extract<Effect3d, { kind: K }> {
  return effects.find((e) => e.kind === kind) as Extract<Effect3d, { kind: K }>;
}

describe('F-236 / SF-236-10 — S9 étape (chapelet)', () => {
  it('zoom sur le chantier ; barre = (done + stepsDone) / total ; phase et étape écrites', () => {
    const plan = sceneEtape(BANC_SCENE_VIEWS['etape'], ctx({ stepsDone: { 's-forge#3': 1 } }));
    expect(plan.focus).toBe('s-forge#3');
    const panel = plan.panels[0];
    expect(panel.anchor).toBe('s-forge#3');
    expect(panel.title).toBe('Ch. 3 — Bascule DNS');
    expect(panel.progress).toBeCloseTo(3 / 5, 5);
    expect(panel.lines.map((l) => l.text)).toEqual(['L’étape avance', 'Phase : Exécution', 'Étape 3 — faite',
      '3/5 étapes']);
  });

  it('jamais au-delà du total ; sans étape cochée pendant le tour, la barre suit l’univers', () => {
    expect(sceneEtape(BANC_SCENE_VIEWS['etape'], ctx({ stepsDone: { 's-forge#3': 9 } })).panels[0].progress).toBe(1);
    expect(sceneEtape(BANC_SCENE_VIEWS['etape'], ctx()).panels[0].progress).toBeCloseTo(2 / 5, 5);
  });

  it('une perle se remplit : l’éclair court du losange à la perle, qui s’allume en vert', () => {
    const plan = sceneEtape(BANC_SCENE_VIEWS['etape'], ctx({ stepsDone: { 's-forge#3': 1 } }));
    expect(effect(plan.effects, 'flow')).toEqual(jasmine.objectContaining({ from: 's-forge#3', to: 's-forge#3/3' }));
    expect(effect(plan.effects, 'pulse')).toEqual(jasmine.objectContaining({ anchor: 's-forge#3/3', tone: 'green' }));
  });

  it('la perle cochée pendant le tour est pleine dans la disposition (avant la relecture de l’univers)', () => {
    const layout = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: new Set(),
      stepsDone: { 's-forge#3': 1 } });
    expect(layout.nodes.filter((n) => n.chantierKey === 's-forge#3' && n.done).length).toBe(3);
  });

  it('le chantier de la scène est posé, allumé, son chapelet déployé (même hors chantier focalisé)', () => {
    const s = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', scene: BANC_SCENE_VIEWS['etape'] };
    const view = universeView(s, false, false);
    expect(view.graph.nodes.get('s-forge#3')).toBe('lit');
    expect(view.layout.nodes.some((n) => n.key === 's-forge#3/1')).toBeTrue();
  });
});

describe('F-236 / SF-236-10 — S11 attente (L-attente, T-L4)', () => {
  it('posée : « personne · catégorie » en teinte d’attente, lien attaché du chantier à la personne', () => {
    const plan = sceneAttente(BANC_SCENE_VIEWS['attente'], ctx());
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ tone: 'wait', anchor: 's-forge#3' }));
    expect(plan.panels[0].lines[0].text).toBe('Zahi Ben · validation');
    expect(effect(plan.effects, 'tether')).toEqual({ kind: 'tether', from: 's-forge#3', to: 'h-cagip:zahi',
      tone: 'wait', broken: false });
  });

  it('levée : le lien se rompt (T-L4) et une lueur verte part du chantier', () => {
    const plan = sceneAttente(BANC_SCENE_VIEWS['attente-levee'], ctx());
    expect(effect(plan.effects, 'tether').broken).toBeTrue();
    expect(effect(plan.effects, 'pulse')).toEqual(jasmine.objectContaining({ tone: 'green', anchor: 's-forge#3' }));
    expect(plan.panels[0].tone).toBe('green');
  });

  it('personne inconnue de l’univers : le lien part vers un point flottant, sans erreur', () => {
    const plan = sceneAttente(sceneOf('S11', 'attente', 'Attente posée', {}, { person: 'Inconnu', attached: true }),
      ctx());
    expect(effect(plan.effects, 'tether').to).toBeNull();
    expect(plan.panels[0].lines[0].text).toBe('Inconnu · attente');
  });
});

describe('F-236 / SF-236-10 — S2 « oui » / question', () => {
  it('devant tout tant qu’il attend ; compte à rebours = remaining (18 s sur 30 s)', () => {
    const plan = sceneOui(BANC_SCENE_VIEWS['oui'], ctx());
    const panel = plan.panels[0];
    expect(panel).toEqual(jasmine.objectContaining({ front: true, tone: 'wait', anchor: CORE_KEY }));
    expect(panel.lines.map((l) => l.text)).toContain('18 s');
    expect(panel.progress).toBeCloseTo(18 / 30, 5);
    expect(effect(plan.effects, 'countdown')).toEqual({ kind: 'countdown', fraction: 18 / 30, tone: 'wait' });
  });

  it('S2 reste dans le pool même quand il déborde (téléphone : 2 panneaux)', () => {
    const others: { id: string; front?: boolean }[] = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    const s2 = { id: 's2', front: true };
    expect(boundPanels([...others, s2], true).map((p) => p.id)).toEqual(['s2', 'a']);
  });

  it('sans échéance : l’arc reste plein, pas de secondes écrites', () => {
    expect(countdownFraction(BANC_SCENE_VIEWS['question'])).toBe(1);
    expect(sceneOui(BANC_SCENE_VIEWS['question'], ctx()).panels[0].progress).toBeUndefined();
  });

  it('le plan de scène S2 passe devant dans le directeur (stageScene)', () => {
    const s = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', scene: BANC_SCENE_VIEWS['oui'] };
    const plan = stageScene(s, universeView(s, false, true), true);
    expect(plan?.panels[0].front).toBeTrue();
  });
});

describe('F-236 / SF-236-10 — S14 points de route, S15 le point', () => {
  it('S14 : une perle par question, traitée = verte', () => {
    const plan = scenePointsDeRoute(BANC_SCENE_VIEWS['points'], ctx());
    expect(effect(plan.effects, 'crown').done).toEqual([true, true, false, false]);
    expect(plan.panels[0].lines.map((l) => l.tone)).toEqual(['green', 'green', undefined, undefined]);
    expect(plan.panels[0].lines[2].hot).toBeTrue();
  });

  it('S15 : vague sur tous les sujets posés du client, un seul travelling de 60° par scène', () => {
    const plan = sceneLePoint(BANC_SCENE_VIEWS['le-point'], ctx());
    expect(effect(plan.effects, 'wave').keys).toEqual(['s-term', 's-forge', 's-doc', 's-web', 's-dup1']);
    expect(plan.travel?.degrees).toBe(60);
    expect(sceneLePoint(BANC_SCENE_VIEWS['le-point'], ctx()).travel).toEqual(plan.travel);
  });

  it('S2, S9, S11, S14, S15 ont leur rendu dédié', () => {
    for (const id of ['S2', 'S9', 'S11', 'S14', 'S15']) {
      expect(Object.hasOwn(SCENES_3D, id)).withContext(id).toBeTrue();
      expect(SGEN_3D_IDS.has(id)).withContext(id).toBeFalse();
    }
  });
});
