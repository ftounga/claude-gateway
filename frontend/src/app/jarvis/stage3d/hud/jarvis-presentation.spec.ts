import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { IDLE_SCREEN, ScreenSignals, stageScene, universeView } from '../jarvis-stage-director';
import { BANC_SCENE_VIEWS } from '../scenes3d/jarvis-scene3d.fixture';
import { PresentationFilter, initials, presentAmbient, presentScene, presentSignals, presentUniverse, tourKeys }
  from './jarvis-presentation';

/** Ce qui ne doit jamais apparaître en présentation : noms de personnes, coût, sorties, détails des scènes. */
const FORBIDDEN = ['Zahi', 'zahi', 'RSSI', 'kubectl', 'TOKEN', 'FORGE-41', 'bascule.ts', 'plan.md', 'Route53',
  'Runbook', '1,23 €', 'Bascule DNS samedi', 'radar sujets CAGIP', 'dossier-bascule'];

function leaks(text: string): string[] {
  return FORBIDDEN.filter((w) => text.includes(w));
}

describe('F-236 / SF-236-23 — mode présentation (filtre pur, avant le dessin)', () => {
  it('initiales : « Zahi Ben » ⇒ « Z. B. » ; vide ⇒ « • »', () => {
    expect(initials('Zahi Ben')).toBe('Z. B.');
    expect(initials('jean-paul')).toBe('J. P.');
    expect(initials('  ')).toBe('•');
    expect(initials(null)).toBe('•');
  });

  it('univers : personnes, attentes, destinataires en initiales ; postes, sujets, chantiers gardés', () => {
    const u = presentUniverse(testUniverse())!;
    const cagip = u.clients[0];
    expect(cagip.people).toEqual([{ name: 'Z. B.', variants: [], role: null }]);
    // « Zahi » désigne Zahi Ben (variante) : mêmes initiales, la liaison chantier → personne reste juste.
    expect(cagip.toSend).toEqual(['Z. B.']);
    expect(cagip.subjects[1].chantiers[0].waitingOn).toEqual(['Z. B.']);
    expect(cagip.name).toBe('CAGIP');
    expect(cagip.subjects[1].name).toBe('Migration Forge');
    expect(leaks(JSON.stringify(u))).toEqual([]);
    expect(presentUniverse(null)).toBeNull();
  });

  it('scènes : aucun détail ni sortie, la forme reste ; le plan 3D de CHAQUE scène du banc est sans fuite', () => {
    const universe = presentUniverse(testUniverse());
    for (const [name, view] of Object.entries(BANC_SCENE_VIEWS)) {
      const scene = presentScene(view)!;
      expect(scene.detail).withContext(name).toBeNull();
      expect(scene.id).toBe(view.id);
      const s: ScreenSignals = { ...IDLE_SCREEN, universe, subjectId: 's-forge', scene, pulses: 3 };
      const plan = stageScene(s, universeView(s, false, false), false);
      expect(leaks(JSON.stringify({ scene, plan }))).withContext(name).toEqual([]);
    }
    expect(presentScene(BANC_SCENE_VIEWS['commande'])?.data?.lines).toBeUndefined();
    expect(presentScene(BANC_SCENE_VIEWS['fichiers'])?.data?.mode).toBe('write');
    expect(presentScene(null)).toBeNull();
  });

  it('ambiance : jamais de coût, ni texte d’appel ou d’alerte ; jamais administrateur', () => {
    const ambient = { hostDown: null, liveSubjects: new Set<string>(), cost: '1,23 €',
      calling: { subjectId: 's-doc', label: 'Zahi a répondu' }, alert: { clientKey: 'h-cagip', label: 'Veille Zahi' } };
    const a = presentAmbient(ambient)!;
    expect(a.cost).toBeNull();
    expect(a.calling).toEqual({ subjectId: 's-doc', label: '' });
    expect(a.alert).toEqual({ clientKey: 'h-cagip', label: '' });
    const s = presentSignals({ ...IDLE_SCREEN, admin: true, ambient, spokenTickets: ['FORGE-41'] });
    expect(s.admin).toBeFalse();
    expect(s.spokenTickets).toEqual([]);
  });

  it('filtre mémorisé : mêmes objets tant que l’entrée ne change pas (plan non recalculé)', () => {
    const f = new PresentationFilter();
    const base: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), scene: BANC_SCENE_VIEWS['commande'] };
    const a = f.signals(base);
    const b = f.signals({ ...base, pulses: 9 });
    expect(b.universe).toBe(a.universe);
    expect(b.scene).toBe(a.scene);
    expect(f.signals({ ...base, universe: testUniverse() }).universe).not.toBe(a.universe);
  });

  it('tour automatique : postes puis sujets (hors terminal du poste)', () => {
    expect(tourKeys(testUniverse())).toEqual(['h-cagip', 's-forge', 's-doc', 's-web', 's-dup1', 'h-eden', 's-runner',
      's-dup2']);
    expect(tourKeys(null)).toEqual([]);
  });
});
