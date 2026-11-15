import { LabelRect, declutter } from './architecture-labels';

function anyOverlap(rects: LabelRect[], shown: boolean[]): boolean {
  const kept = rects.filter((_, i) => shown[i]);
  return kept.some((a, i) => kept.some((b, j) => j > i && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h
    && b.y < a.y + a.h));
}

describe('F-238 / SF-238-09 — étiquettes sans chevauchement', () => {
  it('quatre libellés de zones empilés dans un coin : un seul reste, le plus prioritaire', () => {
    const rects = [0, 1, 2, 3].map((k) => ({ x: 100 + k * 4, y: 100 + k * 3, w: 120, h: 14, priority: 3 - k }));
    const shown = declutter(rects);
    expect(shown).toEqual([false, false, false, true]);
    expect(anyOverlap(rects, shown)).toBeFalse();
  });

  it('les services passent avant les zones ; deux étiquettes éloignées restent toutes deux', () => {
    const rects = [{ x: 0, y: 0, w: 80, h: 14, priority: 2.5 }, { x: 10, y: 5, w: 60, h: 14, priority: 1 },
      { x: 400, y: 300, w: 60, h: 14, priority: 2.1 }];
    expect(declutter(rects)).toEqual([false, true, true]);
  });

  it('nuage aléatoire : jamais deux étiquettes montrées qui se touchent ; étiquette vide écartée', () => {
    let seed = 7;
    const rnd = (): number => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    const rects = Array.from({ length: 80 }, () => ({ x: rnd() * 800, y: rnd() * 600, w: 40 + rnd() * 100, h: 14,
      priority: rnd() * 3 }));
    rects.push({ x: 0, y: 0, w: 0, h: 0, priority: 0 });
    const shown = declutter(rects);
    expect(anyOverlap(rects, shown)).toBeFalse();
    expect(shown[80]).toBeFalse();
    expect(shown.filter(Boolean).length).toBeGreaterThan(10);
  });
});
