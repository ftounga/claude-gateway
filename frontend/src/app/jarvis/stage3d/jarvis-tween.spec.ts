import { ColorTween, Tween, maxStep } from './jarvis-tween';

describe('F-236 / SF-236-02 — interpolations (règles 1 à 3 du §7.3)', () => {
  it('va de la valeur courante à la cible sur la durée, selon la courbe', () => {
    const t = new Tween(0);
    t.set(1, 1000, 400, 'glisse');
    expect(t.value(1000)).toBe(0);
    expect(t.value(1200)).toBeCloseTo(0.5, 2);
    expect(t.value(1400)).toBe(1);
    expect(t.settled(1399)).toBeFalse();
    expect(t.settled(1400)).toBeTrue();
  });

  it('interrompue, repart de la valeur affichée (jamais de retour au début, jamais de téléportation)', () => {
    const t = new Tween(0);
    t.set(1, 0, 1000, 'glisse');
    const mid = t.value(500);
    t.set(0, 500, 1000, 'glisse');
    expect(t.value(500)).toBeCloseTo(mid, 6);
    expect(t.value(1500)).toBe(0);
  });

  it('la même cible ne relance rien', () => {
    const t = new Tween(0);
    t.set(1, 0, 1000, 'glisse');
    t.set(1, 900, 1000, 'glisse');
    expect(t.value(1000)).toBe(1);
  });

  it('indépendante de la cadence : même valeur au même instant, quelles que soient les images intermédiaires', () => {
    const a = new Tween(2);
    const b = new Tween(2);
    a.set(5, 0, 600, 'sortie');
    b.set(5, 0, 600, 'sortie');
    for (let t = 0; t <= 600; t += 33) {
      a.value(t);
    }
    for (let t = 0; t <= 600; t += 16) {
      b.value(t);
    }
    expect(a.value(300)).toBe(b.value(300));
  });

  it('aucun saut : le pas maximal entre deux images reste borné pour chaque courbe', () => {
    for (const curve of ['sortie', 'entree', 'glisse', 'souffle', 'rebond'] as const) {
      // Sur 350 ms (la transition d'état la plus longue parmi les rapides), aucune image ne franchit 30 % de l'écart.
      expect(maxStep(0, 1, 350, curve)).withContext(curve).toBeLessThan(0.3);
    }
  });

  it('couleur : écrite sans allocation dans un tableau possédé', () => {
    const c = new ColorTween([0, 0, 0]);
    c.set([1, 0.5, 0], 0, 100, 'glisse');
    const out = [9, 9, 9];
    c.write(100, out);
    expect(out).toEqual([1, 0.5, 0]);
  });
});
