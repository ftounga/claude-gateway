import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { RenderStats } from '../jarvis-render-stats';
import { activityRings, dayLabels } from './jarvis-activity';

const DAYS = ['2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02'];

describe('F-236 / SF-236-26 — carte d’activité 7 jours (pur)', () => {
  it('par client : tours de ses sujets additionnés jour par jour ; éclat relatif au jour le plus chargé', () => {
    const rings = activityRings({ days: DAYS, subjects: [
      { subjectId: 's-forge', turns: [0, 0, 0, 0, 1, 0, 4] },
      { subjectId: 's-doc', turns: [2, 0, 0, 0, 0, 0, 0] },
      { subjectId: 's-runner', turns: [0, 1, 0, 0, 0, 0, 0] },
    ] }, testUniverse());
    expect(rings.map((r) => r.clientKey)).toEqual(['h-cagip', 'h-eden']);
    expect(rings[0].turns).toEqual([2, 0, 0, 0, 1, 0, 4]);
    expect(rings[0].total).toBe(7);
    expect(rings[0].levels[6]).toBe(1);
    expect(rings[0].levels[1]).toBe(0);
    expect(rings[0].levels[4]).toBe(0.5); // √(1/4)
    expect(rings[1].turns).toEqual([0, 1, 0, 0, 0, 0, 0]);
  });

  it('sujet inconnu de l’univers ignoré (rien d’inventé) ; aucune activité ⇒ éclats nuls', () => {
    const rings = activityRings({ days: DAYS, subjects: [{ subjectId: 'autre-compte', turns: [9, 9, 9, 9, 9, 9, 9] }] },
      testUniverse());
    expect(rings.every((r) => r.total === 0 && r.levels.every((l) => l === 0))).toBeTrue();
  });

  it('réponse illisible ⇒ aucun anneau, aucune erreur ; valeurs négatives ou non numériques ⇒ 0 ; au plus 7 jours', () => {
    for (const bad of [null, undefined, 'x', {}, { days: 'x', subjects: [] }, { days: [], subjects: [] }]) {
      expect(() => activityRings(bad, testUniverse())).not.toThrow();
      expect(activityRings(bad, testUniverse())).toEqual([]);
    }
    expect(activityRings({ days: DAYS, subjects: [] }, null)).toEqual([]);
    const rings = activityRings({ days: [...DAYS, '2026-11-03'], subjects: [
      { subjectId: 's-forge', turns: [-3, 'x', 2, 0, 0, 0, 0, 5] }] }, testUniverse());
    expect(rings[0].turns).toEqual([0, 0, 2, 0, 0, 0, 0]);
  });

  it('libellés des jours ; la mesure compte les vues ouvertes (clés fermées)', () => {
    expect(dayLabels({ days: DAYS, subjects: [] })).toEqual(['mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.', 'lun.']);
    expect(dayLabels(null)).toEqual([]);
    const stats = new RenderStats();
    stats.decided('2d', 'manual', 0);
    stats.view('replays');
    stats.view('replays');
    stats.view('activity');
    expect(stats.report(1000, null, 0)?.render).toEqual(jasmine.objectContaining({ replays: 2, activity: 1 }));
  });
});
