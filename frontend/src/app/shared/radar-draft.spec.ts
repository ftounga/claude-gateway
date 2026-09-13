import { RADAR_DRAFT_MAX_CHARS, radarDraftFrom } from './radar-draft';

/** Un brouillon venu du Radar, lu dans l'état de navigation (F-103 / SF-103-03). */
describe('radarDraftFrom', () => {
  it('rend le brouillon porté par l\'état', () => {
    expect(radarDraftFrom({ navigationId: 3, radarDraft: 'Aide-moi.' })).toBe('Aide-moi.');
  });

  it('ignore un état absent, vide, d\'un autre type ou trop long', () => {
    expect(radarDraftFrom(null)).toBeNull();
    expect(radarDraftFrom('radarDraft')).toBeNull();
    expect(radarDraftFrom({ navigationId: 1 })).toBeNull();
    expect(radarDraftFrom({ radarDraft: '   ' })).toBeNull();
    expect(radarDraftFrom({ radarDraft: 42 })).toBeNull();
    expect(radarDraftFrom({ radarDraft: 'x'.repeat(RADAR_DRAFT_MAX_CHARS + 1) })).toBeNull();
  });
});
