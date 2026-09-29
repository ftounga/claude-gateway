import { compactionMarkerBlock, compactionMarkerLabel, recallMarkerBlock } from './flux-markers';

/**
 * Marqueurs de flux (F-162 / SF-162-03) : les fabriques pures qui posent « Conversation compactée · N
 * tours résumés » et « Détail rappelé · tour N » comme des blocs de transcription à part entière.
 */
describe('flux-markers (F-162 SF-162-03)', () => {
  it('accorde le libellé de compaction au singulier et au pluriel', () => {
    expect(compactionMarkerLabel(1)).toBe('Conversation compactée · 1 tour résumé');
    expect(compactionMarkerLabel(12)).toBe('Conversation compactée · 12 tours résumés');
  });

  it('borne le nombre de tours à zéro et l\'arrondit', () => {
    expect(compactionMarkerLabel(-3)).toBe('Conversation compactée · 0 tour résumé');
  });

  it('pose un bloc marqueur de compaction reconnaissable, sans sortie ni carte', () => {
    const block = compactionMarkerBlock(5);

    expect(block.marker).toEqual({ kind: 'compaction', label: 'Conversation compactée · 5 tours résumés' });
    expect(block.hasOutput).toBeFalse();
    expect(block.card).toBeUndefined();
    expect(block.tool).toBe('compaction');
  });

  it('pose un bloc marqueur de recall qui reprend le repère mis en forme', () => {
    const block = recallMarkerBlock('tour 34');

    expect(block.marker).toEqual({ kind: 'recall', label: 'Détail rappelé · tour 34' });
    expect(block.hasOutput).toBeFalse();
  });
});
