import {
  ArchGestures, DRAG_PX, ORBIT_PER_PX, PINCH_EXPONENT, PINCH_STEP, WHEEL_MAX, WHEEL_STEP,
} from './jarvis-piece-gestures';
import { archVoiceRequest, defaultArchNav, reduceArch } from './jarvis-piece-arch';

describe('Architecture — gestes (retour PO 2026-11-16, SF-238-11)', () => {
  it('glisser (souris ou doigt) fait pivoter ; un petit tremblement ne fait rien', () => {
    const g = new ArchGestures();
    g.down(1, 100, 100, 0);
    expect(g.move(1, 102, 101)).toEqual({ kind: 'none' });
    const turn = g.move(1, 150, 120);
    expect(turn).toEqual({ kind: 'orbit', dAz: -48 * ORBIT_PER_PX, dEl: 19 * ORBIT_PER_PX });
    expect(g.up(1, 150, 120, 200)).toEqual({ kind: 'none' }); // un glissement n'est pas un toucher
    expect(DRAG_PX).toBeGreaterThan(0);
  });

  it('pincer à deux doigts zoome (écarter) et dézoome (rapprocher) vers le milieu des doigts, sans pivoter', () => {
    const g = new ArchGestures();
    g.down(1, 100, 200, 0);
    g.down(2, 200, 200, 10);
    expect(g.pinching).toBeTrue();
    expect(g.move(2, 202, 200)).toEqual({ kind: 'none' }); // sous le seuil de lissage
    const out = g.move(2, 300, 200);
    expect(out.kind).toBe('zoom');
    if (out.kind === 'zoom') {
      expect(out.factor).toBeCloseTo(Math.pow(2, PINCH_EXPONENT), 5);
      expect(out.x).toBe(200);
      expect(out.y).toBe(200);
    }
    const back = g.move(1, 250, 200);
    expect(back.kind === 'zoom' && back.factor < 1).toBeTrue();
    expect(g.up(2, 0, 0, 300)).toEqual({ kind: 'none' });
    // Le doigt restant ne tourne pas le schéma et sa levée n'est pas un toucher.
    expect(g.move(1, 260, 260)).toEqual({ kind: 'none' });
    expect(g.up(1, 260, 260, 320)).toEqual({ kind: 'none' });
  });

  it('SF-238-15 — un pincement normal (écart ×2, par petits pas) zoome de ×1,5 à ×2 ; un fort (×7) ne fait pas ×10', () => {
    const total = (to: number): number => {
      const g = new ArchGestures();
      g.down(1, 500, 300, 0);
      g.down(2, 540, 300, 0);
      let z = 1;
      for (let i = 1; i <= 30; i++) {
        const d = 20 + ((to - 20) * i) / 30;
        const e = g.move(2, 500 + 20 + d, 300);
        const f = g.move(1, 500 + 20 - d, 300);
        for (const ev of [e, f]) {
          z *= ev.kind === 'zoom' ? ev.factor : 1;
        }
      }
      return z;
    };
    const normal = total(40);
    expect(normal).toBeGreaterThanOrEqual(1.5);
    expect(normal).toBeLessThanOrEqual(2);
    expect(total(140)).toBeLessThan(6); // ×7 d'écart ⇒ ≈ ×4,3 avant plafond (×4)
    expect(PINCH_STEP).toBeGreaterThan(1);
  });

  it('molette : ×1,15 par cran vers le pointeur, bornée par événement ; lignes et pages comprises', () => {
    expect(ArchGestures.wheel(-100, 10, 20)).toEqual({ kind: 'zoom', factor: WHEEL_STEP, x: 10, y: 20 });
    const out = ArchGestures.wheel(100, 0, 0);
    expect(out.kind === 'zoom' && Math.abs(out.factor - 1 / WHEEL_STEP) < 1e-9).toBeTrue();
    expect(ArchGestures.wheel(0, 0, 0)).toEqual({ kind: 'none' });
    const fast = ArchGestures.wheel(-2000, 0, 0);
    expect(fast.kind === 'zoom' && fast.factor === WHEEL_MAX).toBeTrue();
    const line = ArchGestures.wheel(-3, 0, 0, 1);
    expect(line.kind === 'zoom' && Math.abs(line.factor - WHEEL_STEP) < 1e-9).toBeTrue();
    // Dix crans : ×4 au plus (plafond de l'architecture appliqué par la scène).
    expect(Math.pow(WHEEL_STEP, 10)).toBeGreaterThan(4);
  });

  it('toucher bref = viser ; toucher trop long = rien (le toucher long replie, à part)', () => {
    const g = new ArchGestures();
    g.down(1, 10, 10, 0);
    expect(g.up(1, 11, 10, 100)).toEqual({ kind: 'tap', x: 11, y: 10 });
    g.down(1, 10, 10, 0);
    expect(g.up(1, 10, 10, 900)).toEqual({ kind: 'none' });
  });

  it('recentrer : la vue d’ensemble, sans focus ni orbite (bouton et « recentre »)', () => {
    const turned = { ...defaultArchNav(), view: 'face' as const, orbit: { az: 1, el: 0.2 },
      focus: { id: 'rdsa', label: 'RDS primaire' } };
    expect(reduceArch(turned, { kind: 'recenter' })).toEqual({ ...defaultArchNav() });
    expect(archVoiceRequest('Recentre le schéma')).toEqual({ kind: 'recenter' });
  });
});
