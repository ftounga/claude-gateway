import {
  DEFAULT_FORGE_DENSITY,
  FORGE_DENSITY_STORAGE_KEY,
  effectiveDensity,
  parseDensity,
  readStoredDensity,
  storeDensity,
} from './forge-density';

/** La densité de « Voir travailler » (F-98 / SF-98-04) : l'URL, le choix retenu, le repli. */
describe('forge-density', () => {
  afterEach(() => localStorage.removeItem(FORGE_DENSITY_STORAGE_KEY));

  it('ne reconnaît que les deux densités', () => {
    expect(parseDensity('apercus')).toBe('apercus');
    expect(parseDensity('flux')).toBe('flux');
    expect(parseDensity('mosaique')).toBeNull();
    expect(parseDensity(null)).toBeNull();
  });

  it('l’URL passe avant le choix retenu, qui passe avant les aperçus', () => {
    expect(effectiveDensity(null)).toBe(DEFAULT_FORGE_DENSITY);
    expect(DEFAULT_FORGE_DENSITY).toBe('apercus');

    storeDensity('flux');
    expect(readStoredDensity()).toBe('flux');
    expect(effectiveDensity(null)).toBe('flux');
    expect(effectiveDensity('inconnue')).toBe('flux');
    expect(effectiveDensity('apercus')).toBe('apercus');
  });

  it('une valeur stockée illisible est ignorée', () => {
    localStorage.setItem(FORGE_DENSITY_STORAGE_KEY, 'n-importe-quoi');

    expect(readStoredDensity()).toBeNull();
    expect(effectiveDensity(null)).toBe('apercus');
  });

  it('un stockage refusé ne casse rien : on ne retient pas, et c’est tout', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('SecurityError');
    spyOn(Storage.prototype, 'setItem').and.throwError('QuotaExceededError');

    expect(() => storeDensity('flux')).not.toThrow();
    expect(readStoredDensity()).toBeNull();
    expect(effectiveDensity(null)).toBe('apercus');
  });
});
