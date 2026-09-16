import { euros, eurosLabel, tjmLabel } from './money';

describe('money (F-124)', () => {
  it('rend un montant entier sans décimales', () => {
    expect(euros(55000)).toContain('550');
    expect(euros(55000)).not.toContain(',');
  });

  it('garde deux décimales quand il y a des centimes', () => {
    expect(euros(55050)).toContain('550');
    expect(euros(55050)).toContain('50');
  });

  it('rend zéro', () => {
    expect(euros(0)).toBe('0');
  });

  it('étiquette un montant et un TJM', () => {
    expect(eurosLabel(55000)).toContain('€');
    expect(tjmLabel(55000)).toContain('€/j');
    expect(tjmLabel(55000)).toContain('550');
  });
});
