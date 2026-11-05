import { MAX_OPUS_KEYS, OpusCards, opusKeys } from './jarvis-card-opus';
import { JarvisCard } from './jarvis-card.models';

describe('F-248 / SF-248-08 — les clés citées par Opus', () => {
  it('tickets et revues reconnus ; numéros seuls et versions ignorés', () => {
    expect(opusKeys('Le PROJ-123 est en revue, la MR projet!42 attend, la #17 aussi, version 2.14.1.'))
      .toEqual(['projet!42', 'PROJ-123']);
    expect(opusKeys('Rien à signaler, Monsieur.')).toEqual([]);
    expect(opusKeys('La !42 est fusionnée.')).toEqual([]);
  });

  it('chaque clé une fois par tour, 6 au plus ; les pancartes revenues sont montrées (ciblées)', () => {
    const turn = new OpusCards('opus:1');
    expect(turn.take('PROJ-1 et PROJ-2')).toEqual(['PROJ-1', 'PROJ-2']);
    expect(turn.take('Encore PROJ-1, et PROJ-3')).toEqual(['PROJ-3']);
    turn.take('AB-1 AB-2 AB-3 AB-4 AB-5');
    expect(turn.take('ZZ-9')).toEqual([]);
    expect(MAX_OPUS_KEYS).toBe(6);
    const card: JarvisCard = { id: 'fait:etat:x', type: 'TICKET', title: 'PROJ-1', fields: [], aliases: [],
      targeted: false };
    expect(turn.add([card])[0].targeted).toBeTrue();
    expect(turn.add([card]).length).toBe(1);
  });
});
