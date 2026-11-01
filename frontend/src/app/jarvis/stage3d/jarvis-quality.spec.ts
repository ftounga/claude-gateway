import { MIN_CHANGE_GAP_MS, QualityGovernor, QualityVerdict } from './jarvis-quality';

/** Fait tourner le gouverneur à intervalle fixe pendant `durationMs` ; renvoie les verdicts non neutres. */
function run(g: QualityGovernor, from: number, durationMs: number, frameMs: number):
    { at: number; v: QualityVerdict }[] {
  const out: { at: number; v: QualityVerdict }[] = [];
  for (let t = from + frameMs; t <= from + durationMs; t += frameMs) {
    const v = g.sample(frameMs, t);
    if (v.kind !== 'keep') {
      out.push({ at: t, v });
    }
  }
  return out;
}

describe('F-236 / SF-236-02 — qualité adaptative (§6.3, horloge simulée)', () => {
  it('fluide ⇒ aucun changement', () => {
    const g = new QualityGovernor('high', 0);
    expect(run(g, 0, 60_000, 16)).toEqual([]);
    expect(g.level).toBe('high');
  });

  it('descend d’un niveau après 2 s au-dessus de 20 ms (une fois la fenêtre de 2 s pleine)', () => {
    const g = new QualityGovernor('high', -MIN_CHANGE_GAP_MS);
    const changes = run(g, 0, 6_000, 25);
    expect(changes.length).toBe(1);
    expect(changes[0].v).toEqual({ kind: 'change', level: 'medium' });
    expect(changes[0].at).toBeGreaterThanOrEqual(3_700);
    expect(changes[0].at).toBeLessThanOrEqual(4_200);
  });

  it('au plus un changement toutes les 5 s', () => {
    const g = new QualityGovernor('high', -MIN_CHANGE_GAP_MS);
    const changes = run(g, 0, 20_000, 25);
    expect(changes.length).toBeGreaterThan(1);
    for (let i = 1; i < changes.length; i++) {
      expect(changes[i].at - changes[i - 1].at).toBeGreaterThanOrEqual(MIN_CHANGE_GAP_MS);
    }
  });

  it('remonte après 10 s sous 13 ms, jamais au-dessus du niveau de départ', () => {
    const g = new QualityGovernor('high', -MIN_CHANGE_GAP_MS);
    const down = run(g, 0, 5_000, 25);
    expect(g.level).toBe('medium');
    const at = down[0].at;
    const up = run(g, at, 30_000, 10);
    expect(up.length).toBe(1);
    expect(up[0].v).toEqual({ kind: 'change', level: 'high' });
    expect(up[0].at - at).toBeGreaterThanOrEqual(10_000);
    expect(run(g, at + 30_000, 60_000, 10)).toEqual([]);
  });

  it('niveau Bas sous 30 i/s pendant 5 s ⇒ repli 2D, une seule fois', () => {
    const g = new QualityGovernor('low', 0);
    const verdicts = run(g, 0, 20_000, 40);
    expect(verdicts.length).toBe(1);
    expect(verdicts[0].v.kind).toBe('fallback2d');
    expect(verdicts[0].at).toBeGreaterThanOrEqual(6_500);
  });

  it('un intervalle énorme (onglet revenu) n’est pas une mesure', () => {
    const g = new QualityGovernor('high', 0);
    run(g, 0, 3_000, 16);
    expect(g.sample(60_000, 63_000)).toEqual({ kind: 'keep' });
    expect(g.average).toBe(0);
    expect(run(g, 63_000, 3_000, 16)).toEqual([]);
  });

  it('tampon circulaire préalloué : des minutes à haute cadence gardent une moyenne exacte', () => {
    const g = new QualityGovernor('high', 0);
    run(g, 0, 120_000, 4);
    expect(g.average).toBeCloseTo(4, 5);
  });
});
