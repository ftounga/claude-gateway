import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { SCENES_3D, SGEN_3D_IDS, scene3dFor } from './index';
import { BANC_SCENE_VIEWS, sceneOf } from './jarvis-scene3d.fixture';
import { ANCHOR_ABOVE, ANCHOR_BELOW, ANCHOR_GLOBE, Effect3d, SceneContext } from './jarvis-scene3d.model';
import { sceneGeneric } from './jarvis-scene-sgen';
import {
  HANDOFF_TRAVEL_DEG, SWARM_BOUND, sceneCreation, sceneEssaim, sceneMemoire, scenePassation, sceneVeille, sceneVideo,
  sceneWeb,
} from './jarvis-scenes-monde';
import { EMPTY_SESSION } from './jarvis-session-memory';

function ctx(extra: Partial<SceneContext> = {}): SceneContext {
  return { universe: testUniverse(), subjectId: 's-forge', nodes: new Set(['h-cagip', 's-forge', 's-doc']),
    pulses: 2, narrow: false, session: EMPTY_SESSION, spokenTickets: [], stepsDone: {}, ...extra };
}

function effect<K extends Effect3d['kind']>(effects: readonly Effect3d[], kind: K): Extract<Effect3d, { kind: K }> {
  return effects.find((e) => e.kind === kind) as Extract<Effect3d, { kind: K }>;
}

describe('F-236 / SF-236-12 — web, création, vidéo', () => {
  it('S6 : barre d’adresse au-dessus du cœur, globe filaire, impulsions globe → cœur', () => {
    const plan = sceneWeb(BANC_SCENE_VIEWS['web'], ctx());
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ anchor: CORE_KEY, kind: 'address', mono: true }));
    expect(plan.panels[0].lines[0].text).toBe('Recherche : TTL DNS bascule Route53');
    expect(effect(plan.effects, 'globe')).toBeDefined();
    expect(effect(plan.effects, 'flow')).toEqual(jasmine.objectContaining({ from: ANCHOR_GLOBE, to: CORE_KEY }));
    const page = sceneWeb(sceneOf('S6', 'web_fetch', 'Page web lue', {}, { address: 'docs.aws.amazon.com',
      web: 'page' }), ctx());
    expect(page.panels[0].lines[0].text).toBe('docs.aws.amazon.com');
  });

  it('S7 : couches qui se construisent sous le titre ; salle de montage = une couche par suivi ; audio = onde', () => {
    const page = sceneCreation(BANC_SCENE_VIEWS['page'], ctx());
    expect(page.panels[0].lines.map((l) => l.text)).toEqual(['Runbook de bascule DNS', 'Page']);
    expect(effect(page.effects, 'cards')).toEqual(jasmine.objectContaining({ layout: 'stack', count: 3 }));
    const montage = sceneCreation(BANC_SCENE_VIEWS['montage'], ctx());
    expect(effect(montage.effects, 'cards').count).toBe(4);
    expect(montage.panels[0].lines).toContain(jasmine.objectContaining({ text: 'Rendu suivi × 3' }));
    const audio = sceneCreation(sceneOf('S7', 'voix_off', 'Voix off', {}, { creation: 'audio' }), ctx());
    expect(audio.effects.map((e) => e.kind)).toEqual(['pulse']);
    const lue = sceneCreation(sceneOf('S7', 'page_lire', 'Lecture d’une page', {}, { creation: 'page' }), ctx());
    expect(lue.panels[0].scroll).toBeTrue();
  });

  it('S5b : la pellicule en arc, le film et l’extraction écrits', () => {
    const plan = sceneVideo(BANC_SCENE_VIEWS['video'], ctx());
    expect(effect(plan.effects, 'cards')).toEqual(jasmine.objectContaining({ layout: 'film', count: 6 }));
    expect(plan.panels[0].lines.map((l) => l.text)).toEqual(['demo-bascule.mp4', 'Extraction des plans · 12 images']);
  });
});

describe('F-236 / SF-236-12 — passation, mémoire, essaim, veille', () => {
  it('S10 : arc lumineux sujet → sujet ; UN SEUL travelling (T-Z3, ≤ 60°) par passation', () => {
    const plan = scenePassation(BANC_SCENE_VIEWS['passation'], ctx());
    expect(effect(plan.effects, 'flow')).toEqual(jasmine.objectContaining({ from: 's-forge', to: 's-doc' }));
    expect(effect(plan.effects, 'flow').lift).toBeGreaterThan(0);
    expect(plan.travel?.degrees).toBe(HANDOFF_TRAVEL_DEG);
    expect(HANDOFF_TRAVEL_DEG).toBeLessThanOrEqual(60);
    // La même scène (relue par le directeur) garde le même travelling : il ne se rejoue pas.
    expect(scenePassation(BANC_SCENE_VIEWS['passation'], ctx()).travel).toEqual(plan.travel);
    expect(scenePassation({ ...BANC_SCENE_VIEWS['passation'], since: 99 }, ctx()).travel?.id)
      .not.toBe(plan.travel?.id);
    expect(plan.focus).toBe('s-doc');
  });

  it('S12 : le souvenir remonte des profondeurs ; la carte du savoir est une constellation au-dessus du cœur', () => {
    const recall = sceneMemoire(BANC_SCENE_VIEWS['souvenir'], ctx());
    expect(recall.panels[0].lines[0]).toEqual({ text: 'Tour 34', hot: true });
    expect(effect(recall.effects, 'flow').from).toBe(ANCHOR_BELOW);
    const carte = sceneMemoire(BANC_SCENE_VIEWS['carte'], ctx());
    expect(effect(carte.effects, 'cloud')).toEqual(jasmine.objectContaining({ anchor: ANCHOR_ABOVE }));
    expect(effect(carte.effects, 'cloud').points.length).toBe(12);
    expect(sceneMemoire(BANC_SCENE_VIEWS['carte'], ctx())).toEqual(carte);
  });

  it('S13 : essaim ≤ 4 sur PC ; téléphone ≤ 2 + « +n »', () => {
    const pc = sceneEssaim(BANC_SCENE_VIEWS['essaim'], ctx());
    expect(effect(pc.effects, 'swarm').count).toBe(SWARM_BOUND.wide);
    expect(pc.panels[0].lines.length).toBe(4);
    const phone = sceneEssaim(BANC_SCENE_VIEWS['essaim'], ctx({ narrow: true }));
    expect(effect(phone.effects, 'swarm').count).toBe(2);
    expect(phone.panels[0].lines.map((l) => l.text).at(-1)).toBe('+2');
  });

  it('S17 : un cadran attaché au sujet ; S16 reste « S-GEN libellée »', () => {
    const plan = sceneVeille(BANC_SCENE_VIEWS['veille'], ctx());
    expect(effect(plan.effects, 'dial')).toEqual({ kind: 'dial', anchor: 's-forge', tone: 'normal' });
    expect(scene3dFor('S16')).toBe(sceneGeneric);
    expect(SGEN_3D_IDS.has('S16')).toBeTrue();
  });

  it('S5b, S6, S7, S10, S12, S13, S17 ont leur rendu dédié ; il ne reste en repli que S16, S18, S19', () => {
    for (const id of ['S5b', 'S6', 'S7', 'S10', 'S12', 'S13', 'S17']) {
      expect(Object.hasOwn(SCENES_3D, id)).withContext(id).toBeTrue();
    }
    expect([...SGEN_3D_IDS].sort()).toEqual(['S16', 'S18', 'S19']);
  });
});
