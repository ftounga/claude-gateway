import { FPS_CLASSES, RenderStats, fpsClass, medianClass } from './jarvis-render-stats';

describe('F-236 / SF-236-06 — mesure du rendu (sans contenu)', () => {
  it('classes d’i/s : 6 classes, bornes 20 / 30 / 40 / 50 / 60', () => {
    expect([10, 25, 35, 45, 55, 60, 120].map(fpsClass)).toEqual([0, 1, 2, 3, 4, 5, 5]);
    expect(FPS_CLASSES.length).toBe(6);
  });

  it('classe médiane d’un histogramme ; vide ⇒ −1', () => {
    expect(medianClass([0, 0, 0, 0, 9, 1])).toBe(4);
    expect(medianClass([5, 0, 0, 0, 0, 5])).toBe(0);
    expect(medianClass([0, 0, 0, 0, 0, 0])).toBe(-1);
  });

  it('session restée en 2D : mode 2d et la première raison', () => {
    const s = new RenderStats();
    s.decided('2d', 'no_webgl', 0);
    s.decided('2d', 'manual', 10);
    expect(s.report(1000, null, 0)).toEqual({ mode: '2d', render: { r_no_webgl: 1 } });
  });

  it('session 3D : secondes par niveau, classe d’i/s p50, gestes, touchers, cerveaux, entrée, bascule en 2D', () => {
    const s = new RenderStats();
    s.decided('3d', null, 0);
    s.entry(false);
    s.shown('high', 0);
    s.levelAt('medium', 60_000);
    s.touch('subject');
    s.touch('core');
    s.touch('client');
    s.brainPass();
    s.decided('2d', 'manual', 90_000);
    const report = s.report(120_000, [0, 0, 1, 2, 7, 0], 3);
    expect(report).toEqual({ mode: '3d', render: {
      entries: 1, t_subject: 1, t_core: 1, t_client: 1, brains: 1, to2d: 1, sec_high: 60, sec_medium: 30,
      fps_50_60: 1, gestures: 3,
    } });
  });

  it('un seul compte-rendu par ouverture', () => {
    const s = new RenderStats();
    s.decided('2d', 'sober', 0);
    expect(s.report(1, null, 0)).not.toBeNull();
    expect(s.report(2, null, 0)).toBeNull();
  });

  it('entrée sautée comptée à part', () => {
    const s = new RenderStats();
    s.shown('low', 0);
    s.entry(true);
    expect(s.report(500, null, 0)?.render['entries_skipped']).toBe(1);
  });
});
