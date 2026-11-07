import { BLINK_S, FACE_MOODS, blinkAt, faceLookAt, faceMoodTargets } from './jarvis-face-motion';

const still = { open: 0, wide: 0, round: 0, press: 0 };

function run(mood: Parameters<typeof faceLookAt>[1], seconds: number, mouth = still, from = faceMoodTargets('repos')) {
  for (let t = 0; t < seconds; t += 1 / 60) {
    faceLookAt(from, mood, t, 10 + t, 1 / 60, 0.5, mouth, false);
  }
  return from;
}

describe('F-250 / SF-250-05 — le visage vit', () => {
  it('clignements : déterministes, 2,4 à 6 s d’écart, jamais au-delà de 3 Hz', () => {
    const starts: number[] = [];
    let was = false;
    for (let t = 0; t < 120; t += 0.005) {
      const closed = blinkAt(t) > 0.5;
      if (closed && !was) {
        starts.push(t);
      }
      was = closed;
    }
    expect(starts.length).toBeGreaterThan(18);
    for (let k = 1; k < starts.length; k++) {
      expect(starts[k] - starts[k - 1]).toBeGreaterThan(0.33);
    }
    expect(blinkAt(12.345)).toBe(blinkAt(12.345));
    expect(BLINK_S).toBeLessThan(0.2);
  });

  it('réflexion : paupières mi-closes, regard en haut à gauche, éclairs synaptiques', () => {
    const look = run('reflexion', 3);
    expect(look.lidBase).toBeCloseTo(FACE_MOODS.reflexion.lid, 1);
    expect(look.gx).toBeLessThan(-0.01);
    expect(look.gy).toBeGreaterThan(0.005);
    expect(look.think).toBeGreaterThan(0.9);
  });

  it('écoute : yeux grand ouverts, sourcils levés, ondes convergentes', () => {
    const look = run('ecoute', 3);
    expect(look.lidBase).toBeGreaterThan(0.97);
    expect(look.brow).toBeGreaterThan(0.01);
    expect(look.ringMode).toBe(1);
  });

  it('parole : la bouche suit les lèvres demandées ; coupure : fermée en ≈ 120 ms', () => {
    const look = run('parole', 0.5, { open: 0.9, wide: 0.15, round: 0, press: 0 });
    expect(look.mouth.open).toBeGreaterThan(0.85);
    expect(look.ringMode).toBe(2);
    run('coupure', 0.12, still, look);
    expect(look.mouth.open).toBeLessThan(0.05);
    expect(look.gx).toBeCloseTo(0, 2);
  });

  it('veille : points à 20 %, plus de fil de fer', () => {
    const look = run('veille', 4);
    expect(look.points).toBeLessThan(0.25);
    expect(look.wire).toBeLessThan(0.05);
  });

  it('mouvements réduits : ni clignement, ni balayage, ni phase', () => {
    const look = faceMoodTargets('repos');
    const scan = look.scanY;
    for (let t = 0; t < 10; t += 1 / 60) {
      faceLookAt(look, 'repos', t, t, 1 / 60, 0, still, true);
      expect(look.lid).toBeCloseTo(look.lidBase, 6);
    }
    expect(look.scanY).toBe(scan);
    expect(look.auraPhase).toBe(0);
  });
});
