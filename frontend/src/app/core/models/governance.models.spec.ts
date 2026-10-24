import { fileKindLabel } from './governance.models';

describe('fileKindLabel', () => {
  it('dit le genre de chaque fichier en un mot (F-215 / SF-215-03 : guide)', () => {
    expect(fileKindLabel('SKILL')).toBe('skill');
    expect(fileKindLabel('AGENT')).toBe('agent');
    expect(fileKindLabel('GUIDE')).toBe('guide');
    expect(fileKindLabel('MAP')).toBe('carte');
    expect(fileKindLabel('TEMPLATE')).toBe('gabarit');
  });
});
