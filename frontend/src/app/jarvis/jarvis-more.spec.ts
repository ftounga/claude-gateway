import {
  END_PHRASE, MAX_SECTION_WORDS, MoreReader, detailSections, essentialSpeech, isMoreAssent, isMoreCommand, isMoreMark,
} from './jarvis-more';
import { spokenWordCount } from './jarvis-turn-clock';

/** F-240 / SF-240-04 — « dis-m'en plus » descend d'un étage dans la réponse déjà calculée. */
describe('« Dis-m’en plus » (F-240 / SF-240-04)', () => {
  it('reconnaît la liste fermée, accents, « Jarvis » en tête ; « continue » est exclu', () => {
    for (const said of ['Dis-m’en plus.', 'dites-m\'en plus', 'Jarvis, développe.', 'Détaille', 'Plus de détails ?',
      'Encore.', 'La suite ?', 'Et ensuite ?', 'Développe, Monsieur…', 'Tu peux développer ?']) {
      expect(isMoreCommand(said)).withContext(said).toBeTrue();
    }
    for (const said of ['Continue.', 'Développe le ticket LDIC-223', 'Bonjour', 'oui', 'Vérifie.', '']) {
      expect(isMoreCommand(said)).withContext(said).toBeFalse();
    }
  });

  it('un « oui », « vas-y », « dis-le » est un acquiescement (valable seulement après une invitation)', () => {
    expect(isMoreAssent('Oui.')).toBeTrue();
    expect(isMoreAssent('Vas-y.')).toBeTrue();
    expect(isMoreAssent('Dites-le, Jarvis')).toBeTrue();
    expect(isMoreAssent('Non.')).toBeFalse();
    expect(isMoreAssent('Oui, et regarde aussi le ticket 12')).toBeFalse();
  });

  it('la ligne-marque du cerveau rapide n’est jamais dite', () => {
    expect(isMoreMark('⤵')).toBeTrue();
    expect(isMoreMark(' ⤵ ')).toBeTrue();
    expect(isMoreMark('Oui.')).toBeFalse();
  });

  it('1 → 2 → 3 → sections → fin, sans tableau ni code lus', () => {
    const reader = new MoreReader('🎙 Deux tickets sont bloqués.\n↪ Il y a aussi Helios, je vous le dis ?\n'
      + '<<essentiel>>\n- ORN-41 : export PDF\n- ORN-44 : droits d’accès\n- Helios : bascule jeudi\n<</essentiel>>\n'
      + '## Tickets\nLes deux tickets attendent la recette de Claire Dumas.\n| id | état |\n|---|---|\n| 41 | bloqué |\n'
      + '## Helios\nLa bascule des flux est prévue jeudi.\n```bash\nkubectl get pods\n```');
    expect(reader.depth).toBe(1);
    const essential = reader.next();
    expect(essential).toEqual({ kind: 'essential', depth: 2,
      speech: 'ORN-41 : export PDF. ORN-44 : droits d’accès. Helios : bascule jeudi.' });
    const first = reader.next();
    expect(first.kind).toBe('detail');
    expect(first.depth).toBe(3);
    expect(first.speech).toBe('Tickets. Les deux tickets attendent la recette de Claire Dumas. Le tableau est à l’écran.');
    const second = reader.next();
    expect(second.speech).toBe('Helios. La bascule des flux est prévue jeudi. Le code est à l’écran.');
    expect(reader.next()).toEqual({ kind: 'end', speech: END_PHRASE, depth: null });
    expect(reader.next().kind).toBe('end');
    expect(reader.depth).toBe(3);
  });

  it('sans bloc essentiel ni détail : la fin tout de suite', () => {
    const reader = new MoreReader('🎙 C’est fait.');
    expect(reader.next().kind).toBe('end');
  });

  it('une section ne dépasse jamais 60 mots ; un essentiel vide ne se dit pas', () => {
    const long = Array.from({ length: 12 }, (_, i) => `Phrase numéro ${i} avec quelques mots de plus.`).join(' ');
    const sections = detailSections(long);
    expect(sections.length).toBe(1);
    expect(spokenWordCount(sections[0])).toBeLessThanOrEqual(MAX_SECTION_WORDS);
    const oneHuge = 'mot '.repeat(100).trim();
    expect(detailSections(oneHuge)[0].endsWith('…')).toBeTrue();
    expect(spokenWordCount(detailSections(oneHuge)[0])).toBe(MAX_SECTION_WORDS);
    expect(essentialSpeech(null)).toBe('');
    expect(detailSections('Un.\n\nDeux.')).toEqual(['Un.', 'Deux.']);
  });
});
