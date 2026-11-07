import { seededRandom } from '../stage3d/jarvis-banc';
import {
  EYE, FACE_STEP, FEATURE_KIND, LIP_Y, buildFaceFeatures, buildFaceGrid, deformMouth, halfWidth, surfaceZ,
} from './jarvis-face-geometry';

describe('F-250 / SF-250-04 — la géométrie du visage', () => {
  it('≈ 5 900 points au PC, ≈ 2 900 au téléphone (cadrage §4, §6)', () => {
    const pc = buildFaceGrid(FACE_STEP.high, 2, 1, seededRandom(250));
    const phone = buildFaceGrid(FACE_STEP.low, 1, 1, seededRandom(250));
    expect(pc.count).toBeGreaterThan(5000);
    expect(pc.count).toBeLessThan(7000);
    expect(phone.count).toBeGreaterThan(2400);
    expect(phone.count).toBeLessThan(3400);
    // Segments : ≈ 6 000 au PC, ≈ 2 500-6 000 au téléphone (une maille sur une, pas plus large).
    expect(pc.wire.length / 2).toBeLessThan(7000);
  });

  it('même graine ⇒ même visage ; indices du fil de fer valides', () => {
    const a = buildFaceGrid(FACE_STEP.medium, 2, 1, seededRandom(7));
    const b = buildFaceGrid(FACE_STEP.medium, 2, 1, seededRandom(7));
    expect(a.rest).toEqual(b.rest);
    expect(Math.max(...Array.from(a.wire))).toBeLessThan(a.count);
  });

  it('le fil de fer est coupé sur la fente de la bouche (elle peut s’ouvrir)', () => {
    const g = buildFaceGrid(FACE_STEP.high, 1, 1, seededRandom(1));
    for (let k = 0; k < g.wire.length; k += 2) {
      const a = g.wire[k];
      const b = g.wire[k + 1];
      const across = g.above[a] !== g.above[b];
      const near = Math.min(Math.abs(g.rest[a * 3]), Math.abs(g.rest[b * 3])) < 0.23
        && Math.abs(g.rest[a * 3 + 1] - LIP_Y) < 0.1;
      expect(across && near).toBeFalse();
    }
  });

  it('visage générique : ovale symétrique, nez en relief, orbites creusées', () => {
    expect(halfWidth(0)).toBeCloseTo(0.75, 1);
    expect(halfWidth(1.1)).toBe(0);
    expect(surfaceZ(0.2, 0)).toBeCloseTo(surfaceZ(-0.2, 0), 6);
    expect(surfaceZ(0, -0.1)).toBeGreaterThan(surfaceZ(0.15, -0.1));
    expect(surfaceZ(EYE.x, EYE.y + 0.02)).toBeLessThan(surfaceZ(EYE.x, EYE.y + 0.14));
  });

  it('la bouche : ouverte, la lèvre inférieure descend ; fermée (M B P), elle ne bouge pas', () => {
    const out = [0, 0, 0];
    const open = { open: 0.9, wide: 0.15, round: 0, press: 0 };
    deformMouth(0, LIP_Y - 0.01, 0.5, false, open, out);
    expect(out[1]).toBeLessThan(LIP_Y - 0.1);
    deformMouth(0, LIP_Y - 0.01, 0.5, false, { open: 0.9, wide: 0, round: 0, press: 1 }, out);
    expect(out[1]).toBeGreaterThan(LIP_Y - 0.03);
    deformMouth(0.15, LIP_Y, 0.5, true, { open: 0, wide: 0, round: 1, press: 0 }, out);
    expect(out[0]).toBeLessThan(0.15);
  });

  it('les traits : toutes les familles présentes, segments par paires', () => {
    const f = buildFaceFeatures(1);
    expect(f.count % 2).toBe(0);
    const kinds = new Set(Array.from(f.kind));
    for (const kind of Object.values(FEATURE_KIND)) {
      expect(kinds.has(kind)).withContext(`famille ${kind}`).toBeTrue();
    }
    expect(buildFaceFeatures(0.7).count).toBeLessThan(f.count);
  });
});
