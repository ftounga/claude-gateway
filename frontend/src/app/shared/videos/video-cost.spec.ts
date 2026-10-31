import { costLine, costShort, formatEur, formatRange } from './video-cost';

/** Le coût d'une vidéo à l'écran (F-216 / SF-216-03), en euros (F-224 / SF-224-06). */
describe('video-cost', () => {
  it('écrit un montant en euros à la française', () => {
    expect(formatEur(5.18)).toBe('5,18 €');
    expect(formatEur(0)).toBe('0,00 €');
  });

  it('écrit une fourchette, ou un seul montant si les bornes sont égales', () => {
    expect(formatRange(4.41, 6.63)).toBe('4,41 à 6,63 €');
    expect(formatRange(1.38, 1.38)).toBe('1,38 €');
    expect(formatRange(null, null)).toBeNull();
  });

  it('compose la ligne sous le lecteur, en euros', () => {
    expect(costLine({ costUsd: 5.63, costEur: 5.18, estimateLowEur: 4.41, estimateHighEur: 6.63 }))
      .toBe('Coût : 5,18 € (estimé 4,41 à 6,63 €)');
    expect(costLine({ costUsd: 5.63, costEur: 5.18 })).toBe('Coût : 5,18 €');
    expect(costLine({ costUsd: null })).toBe('Coût non mesuré (publiée sans devis)');
    expect(costLine({ costUsd: 12.4, costEur: 11.41, estimateLowEur: 9.2, estimateHighEur: 13.8 }, 'Coût de la série'))
      .toBe('Coût de la série : 11,41 € (estimé 9,20 à 13,80 €)');
  });

  it('jamais un dollar présenté comme euro : mesuré sans euros (passerelle antérieure) = « — »', () => {
    expect(costLine({ costUsd: 5.63, estimateLowUsd: 4.8, estimateHighUsd: 7.2 })).toBe('Coût : —');
    expect(costShort({ costUsd: 5.63 })).toBe('—');
  });

  it('rend le coût court seulement s\'il est mesuré', () => {
    expect(costShort({ costUsd: 2.05, costEur: 1.89 })).toBe('1,89 €');
    expect(costShort({ costUsd: null })).toBeNull();
    expect(costShort(null)).toBeNull();
  });
});
