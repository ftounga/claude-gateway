import { GENERIC_SCENE, SceneRegistry } from '../../scenes/jarvis-scene-registry';
import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { IDLE_SCREEN, ScreenSignals, stageScene, universeView } from '../jarvis-stage-director';
import { SCENES_3D, SGEN_3D_IDS, directScene, knownSceneIds, scene3dFor } from './index';
import { BANC_SCENE_VIEWS, sceneOf } from './jarvis-scene3d.fixture';
import { SceneContext } from './jarvis-scene3d.model';
import { sceneGeneric } from './jarvis-scene-sgen';
import { EMPTY_SESSION } from './jarvis-session-memory';

function ctx(extra: Partial<SceneContext> = {}): SceneContext {
  return { universe: testUniverse(), subjectId: 's-forge', nodes: new Set(['h-cagip', 's-forge', 's-forge#3']),
    pulses: 4, narrow: false, session: EMPTY_SESSION, spokenTickets: [], stepsDone: {}, ...extra };
}

describe('F-236 / SF-236-08 — registre des scènes 3D et garde', () => {
  it('GARDE : chaque scène du registre F-234 (et du lecteur) a un rendu 3D dédié OU retombe déclarativement sur S-GEN', () => {
    for (const id of knownSceneIds()) {
      expect(Object.hasOwn(SCENES_3D, id) || SGEN_3D_IDS.has(id))
        .withContext(`scène ${id} : la déclarer dans SCENES_3D ou SGEN_3D_IDS (review-checklist)`).toBeTrue();
    }
  });

  it('GARDE : une scène nouvelle au registre sans déclaration 3D est détectée', () => {
    const registry: SceneRegistry = { tools: { neuf: { scene: 'S99', libelle: 'Neuf' } }, prefixes: {}, steps: {} };
    const undeclared = [...knownSceneIds(registry)].filter((id) => !Object.hasOwn(SCENES_3D, id) && !SGEN_3D_IDS.has(id));
    expect(undeclared).toEqual(['S99']);
  });

  it('un rendu dédié et un repli S-GEN déclaré ne se recouvrent jamais', () => {
    for (const id of Object.keys(SCENES_3D)) {
      expect(SGEN_3D_IDS.has(id)).withContext(id).toBeFalse();
    }
  });

  it('une scène inconnue (outil futur) retombe sur S-GEN 3D, jamais sur rien', () => {
    expect(scene3dFor('S-INCONNUE')).toBe(sceneGeneric);
    expect(scene3dFor(GENERIC_SCENE)).toBe(sceneGeneric);
    expect(directScene(null, ctx())).toBeNull();
  });
});

describe('F-236 / SF-236-08 — S-GEN 3D', () => {
  it('libellé du registre en petit panneau ancré au cœur, « × n » de la rafale, détail, entité allumée', () => {
    const plan = sceneGeneric(BANC_SCENE_VIEWS['sgen'], ctx());
    expect(plan.rendered).toBe('generic');
    expect(plan.panels.length).toBe(1);
    // Ancré à l'entité que la caméra cadre (sinon au cœur).
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ anchor: 's-forge', tone: 'normal',
      title: 'Radar · sujets du client × 3' }));
    expect(plan.panels[0].lines.map((l) => l.text)).toEqual(['Migration Forge', 'radar sujets CAGIP']);
    expect(plan.focus).toBe('s-forge');
  });

  it('une impulsion par appel : la séquence suit le compteur d’appels du tour', () => {
    expect(sceneGeneric(BANC_SCENE_VIEWS['sgen'], ctx({ pulses: 7 })).effects)
      .toEqual([{ kind: 'pulse', anchor: CORE_KEY, tone: 'normal', seq: 7 }]);
  });

  it('une rafale déjà comptée par son libellé n’ajoute pas « × n »', () => {
    const plan = sceneGeneric(sceneOf('GENERIQUE', 'bash', '3 commandes sur le poste', { count: 3 }), ctx());
    expect(plan.panels[0].title).toBe('3 commandes sur le poste');
  });

  it('entité absente de l’univers posé ⇒ le sujet courant ; aucun des deux ⇒ aucun focus', () => {
    const away = sceneOf('GENERIQUE', 'x', 'X', { entity: { kind: 'subject', key: 's-ailleurs', label: 'Ailleurs',
      clientKey: 'h-eden' } });
    expect(sceneGeneric(away, ctx()).focus).toBeNull();
    expect(sceneGeneric(away, ctx({ subjectId: null })).panels[0].anchor).toBe(CORE_KEY);
    expect(sceneGeneric({ ...away, entity: null }, ctx()).focus).toBe('s-forge');
  });

  it('une autorisation épinglée : teinte d’attente, devant tout', () => {
    const plan = sceneGeneric(sceneOf('S2', 'confirm', 'J’attends votre « oui »', { pinned: true }), ctx());
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ tone: 'wait', front: true }));
  });
});

describe('F-236 / SF-236-08 — le directeur et la scène 3D', () => {
  function signals(extra: Partial<ScreenSignals> = {}): ScreenSignals {
    return { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', scene: BANC_SCENE_VIEWS['sgen'],
      pulses: 2, ...extra };
  }

  it('l’entité de la scène en cours est allumée dans l’univers (D2 : ≤ 3)', () => {
    const view = universeView(signals({ scene: sceneOf('GENERIQUE', 'x', 'X', { entity: { kind: 'chantier',
      key: 's-forge#3', label: 'Chantier 3', clientKey: 'h-cagip', subjectId: 's-forge' } }) }), false, false);
    expect(view.graph.nodes.get('s-forge#3')).toBe('lit');
  });

  it('le plan de scène suit la scène en cours (S-GEN pour une scène générique)', () => {
    const s = signals();
    const plan = stageScene(s, universeView(s, false, false), false);
    expect(plan?.id).toBe('GENERIQUE');
    expect(plan?.panels.length).toBe(1);
    expect(plan?.effects[0]).toEqual(jasmine.objectContaining({ kind: 'pulse', seq: 2 }));
  });

  it('sans scène : aucun plan de scène', () => {
    const s = signals({ scene: null });
    expect(stageScene(s, universeView(s, false, false), false)).toBeNull();
  });
});
