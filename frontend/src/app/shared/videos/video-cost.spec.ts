import { costLine, costShort, formatRange, formatUsd } from './video-cost';

/** Le coût d'une vidéo à l'écran (F-216 / SF-216-03). */
describe('video-cost', () => {
  it('écrit un montant à la française', () => {
    expect(formatUsd(5.63)).toBe('5,63 $');
    expect(formatUsd(0)).toBe('0,00 $');
  });

  it('écrit une fourchette, ou un seul montant si les bornes sont égales', () => {
    expect(formatRange(4.8, 7.2)).toBe('4,80 à 7,20 $');
    expect(formatRange(1.5, 1.5)).toBe('1,50 $');
    expect(formatRange(null, null)).toBeNull();
  });

  it('compose la ligne sous le lecteur', () => {
    expect(costLine({ costUsd: 5.63, estimateLowUsd: 4.8, estimateHighUsd: 7.2 }))
      .toBe('Coût : 5,63 $ (estimé 4,80 à 7,20 $)');
    expect(costLine({ costUsd: 5.63 })).toBe('Coût : 5,63 $');
    expect(costLine({ costUsd: null })).toBe('Coût non mesuré (publiée sans devis)');
    expect(costLine({ costUsd: 12.4, estimateLowUsd: 10, estimateHighUsd: 15 }, 'Coût de la série'))
      .toBe('Coût de la série : 12,40 $ (estimé 10,00 à 15,00 $)');
  });

  it('rend le coût court seulement s\'il est mesuré', () => {
    expect(costShort({ costUsd: 2.05 })).toBe('2,05 $');
    expect(costShort({ costUsd: null })).toBeNull();
    expect(costShort(null)).toBeNull();
  });
});
