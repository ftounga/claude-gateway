import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { ENTRY_FULL_MS } from '../jarvis-camera-rules';
import { layoutUniverse3d } from '../jarvis-layout-3d';
import { linkGraph } from '../jarvis-link-graph';
import { PANEL_POOL, boundPanels } from '../jarvis-panels';
import { postChain } from '../jarvis-post-chain';
import { IDLE_SCREEN, universeView } from '../jarvis-stage-director';
import { SWARM_BOUND } from '../scenes3d/jarvis-scenes-monde';
import { cockpitMode } from './jarvis-cockpit-layout';
import {
  PC_PROFILE, PHONE_ENTRY_MS, PHONE_PROFILE, PHONE_VIEWS, deviceProfile, levelBudget,
} from './jarvis-device-profile';

/** F-236 / SF-236-20 — la matrice §12.2, ligne par ligne : configuration par profil et ce que les consommateurs en font. */
describe('F-236 / SF-236-20 — téléphone allégé (matrice §12.2)', () => {
  it('le profil suit l’appareil', () => {
    expect(deviceProfile(true)).toBe(PHONE_PROFILE);
    expect(deviceProfile(false)).toBe(PC_PROFILE);
  });

  it('cœur et états : restent, particules réduites ; « oui », Montrer, À envoyer restent (rien ne les retire)', () => {
    expect(PHONE_PROFILE.coreParticles).toBe('reduced');
    expect(PC_PROFILE.coreParticles).toBe('full');
  });

  it('univers : clients restent ; sujets et chantiers du client courant ou cités seulement (D7)', () => {
    expect(PHONE_PROFILE.subjects).toBe('current-or-cited');
    const phone = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: true, shown: new Set() });
    expect(phone.nodes.filter((n) => n.kind === 'client').length).toBe(2);
    expect(phone.nodes.some((n) => n.kind === 'subject' && n.clientKey === 'h-eden')).toBeFalse();
    const pc = layoutUniverse3d(testUniverse(), { subjectId: 's-forge', narrow: false, shown: new Set() });
    expect(pc.nodes.some((n) => n.kind === 'subject' && n.clientKey === 'h-eden')).toBeTrue();
  });

  it('liaisons : le chemin du focus seulement, le reste absent (pas latent) ; PC : toutes', () => {
    expect(PHONE_PROFILE.links).toBe('focus-path');
    const s = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', lit: ['s-forge#3'] };
    const phone = universeView(s, false, true).graph;
    expect(phone.links.length).toBeGreaterThan(0);
    expect(phone.links.every((l) => l.state === 'active' || l.state === 'lit')).toBeTrue();
    const pc = universeView(s, false, false).graph;
    expect(pc.links.some((l) => l.state === 'latent')).toBeTrue();
    // Sans focus ni sujet : rien n'est tracé au téléphone.
    const layout = layoutUniverse3d(testUniverse(), { subjectId: null, narrow: true, shown: new Set() });
    expect(linkGraph(layout, { subjectId: null, lit: [], shown: null, asleep: false, narrow: true }).links).toEqual([]);
  });

  it('scènes : un seul panneau ancré, deux pour S4 ; « devant tout » gardé ; PC jusqu’à 8', () => {
    const panels = [{ id: 'a' }, { id: 'b' }, { id: 'c', front: true }];
    expect(boundPanels(panels, true, 'S3').map((p) => p.id)).toEqual(['c']);
    expect(boundPanels(panels, true, 'S4').map((p) => p.id)).toEqual(['c', 'a']);
    expect(boundPanels(panels, true, null).length).toBe(1);
    expect(boundPanels(panels, false, 'S3').length).toBe(3);
    expect(PHONE_PROFILE.panelLimit('S4')).toBeLessThanOrEqual(PANEL_POOL.narrow);
  });

  it('entrée cinématique : 1,2 s au téléphone, 2,6 s au PC', () => {
    expect(PHONE_PROFILE.entryMs).toBe(PHONE_ENTRY_MS);
    expect(PHONE_ENTRY_MS).toBe(1200);
    expect(PC_PROFILE.entryMs).toBe(ENTRY_FULL_MS);
  });

  it('panneaux latéraux, journée, météo, personnes, chronologies, inspecteur ⇒ volets « ☰ Vues » au téléphone', () => {
    expect(PHONE_PROFILE.views).toBe('volets');
    expect(PC_PROFILE.views).toBe('panels');
    expect(cockpitMode({ width: 390, narrow: true, shown3d: true, sober: false })).toBe('phone');
    expect(PHONE_VIEWS.map((v) => v.id)).toEqual(['journee', 'meteo', 'personnes', 'chronologies', 'inspecteur']);
  });

  it('tableaux flottants, mur de tickets, horloge en 3D : retirés au téléphone (volets)', () => {
    expect(PHONE_PROFILE.floatingBoards).toBeFalse();
    expect(PC_PROFILE.floatingBoards).toBeTrue();
  });

  it('décor : nébuleuse, poussière, profondeur de champ, verre flouté, aberration, SMAA retirés ; étoiles simples', () => {
    for (const level of ['high', 'medium', 'low'] as const) {
      const phone = levelBudget(level, true);
      expect(phone.nebula).withContext(level).toBeFalse();
      expect(phone.dust).withContext(level).toBe(0);
      expect(phone.starLayers).withContext(level).toBe(1);
      const chain = postChain(level, true);
      expect(chain.bokeh || chain.smaa || chain.finish).withContext(level).toBeFalse();
    }
    expect(PHONE_PROFILE.nebula || PHONE_PROFILE.dust || PHONE_PROFILE.bokeh || PHONE_PROFILE.glassBlur
      || PHONE_PROFILE.aberration || PHONE_PROFILE.smaa).toBeFalse();
    expect(levelBudget('high', false)).toEqual(jasmine.objectContaining({ nebula: true, dust: 600, starLayers: 3 }));
  });

  it('sol polaire simplifié (grille seule) ; essaim ≤ 2 ; aucune dérive automatique ; vue cockpit sans objet', () => {
    expect(PHONE_PROFILE.floor).toBe('grid');
    expect(levelBudget('high', true).floorRings).toBeLessThanOrEqual(3);
    expect(PHONE_PROFILE.swarm).toBe(SWARM_BOUND.narrow);
    expect(PHONE_PROFILE.autoDrift).toBeFalse();
    expect(PC_PROFILE.autoDrift).toBeTrue();
    expect(PHONE_PROFILE.cockpitWindow).toBeFalse();
  });
});
