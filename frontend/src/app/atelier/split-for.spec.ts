import { splitFor } from './atelier.component';
import { AtelierLane } from '../core/services/atelier.service';

/** F-221 / SF-221-03 — la décision du split, en fonction pure. */
describe('splitFor (F-221)', () => {
  const lane = (chantier: number | null, startedAt: number, autonomy = true, live = false): AtelierLane =>
    ({ chantier, startedAt, autonomy, live });

  it('un seul chantier actif, celui qu\'on montre : pas de split', () => {
    expect(splitFor([lane(7, 1)], 7, new Set()).right).toBeNull();
  });

  it('deux chantiers actifs : la droite prend l\'autre ; le fil commun ne compte pas', () => {
    const decision = splitFor([lane(null, 0, false, true), lane(7, 1), lane(8, 2)], 7, new Set());
    expect(decision.right?.chantier).toBe(8);
    expect(decision.others).toBe(0);
  });

  it('plus de deux : le plus ancien à droite, les autres comptés', () => {
    const decision = splitFor([lane(9, 5), lane(7, 1), lane(8, 3)], 7, new Set());
    expect(decision.right?.chantier).toBe(8);
    expect(decision.others).toBe(1);
  });

  it('un volet refermé ne revient pas tant que son chantier est actif, puis il est oublié', () => {
    const dismissed = new Set([8]);
    expect(splitFor([lane(7, 1), lane(8, 2)], 7, dismissed).right).toBeNull();
    expect(dismissed.has(8)).toBeTrue();
    splitFor([lane(7, 1)], 7, dismissed);
    expect(dismissed.has(8)).toBeFalse();
  });
});
