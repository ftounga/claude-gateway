import { CAMERA_FOV } from '../stage3d/jarvis-camera-rules';
import {
  FACE_MIN_SCALE, FACE_SCREEN_SHARE, facePresenceTarget, faceScaleFor,
} from './jarvis-face-stage';

describe('F-250 / SF-250-05 / SF-250-07 — la place du visage', () => {
  it('le visage est le cœur quand Jarvis dialogue ; cœur en veille ; dissous sous le grand cerveau sauf pour parler', () => {
    for (const mood of ['repos', 'ecoute', 'reflexion', 'parole', 'coupure', 'oui'] as const) {
      expect(facePresenceTarget(mood, 0, 0)).withContext(mood).toBe(1);
    }
    expect(facePresenceTarget('veille', 0, 0)).toBe(0);
    expect(facePresenceTarget('reflexion', 0.9, 0)).toBe(0);
    expect(facePresenceTarget('repos', 0.9, 0)).toBe(0);
    expect(facePresenceTarget('parole', 0.9, 0)).toBe(1);
    expect(facePresenceTarget('ecoute', 0.9, 0)).toBe(1);
  });

  it('taille : la moitié de la hauteur de l’écran, bornée en largeur au téléphone, jamais sous le cœur', () => {
    const d = 20;
    const visibleH = 2 * d * Math.tan((CAMERA_FOV * Math.PI) / 360);
    const pc = faceScaleFor(d, 16 / 9, CAMERA_FOV);
    expect(pc * 1.86).toBeCloseTo(FACE_SCREEN_SHARE * visibleH, 3);
    const phone = faceScaleFor(d, 390 / 844, CAMERA_FOV);
    expect(phone).toBeLessThan(pc);
    expect(phone * 1.52).toBeLessThanOrEqual(0.7 * visibleH * (390 / 844) + 1e-6);
    expect(faceScaleFor(1, 1, CAMERA_FOV)).toBe(FACE_MIN_SCALE);
  });
});
