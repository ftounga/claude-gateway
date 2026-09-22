import {
  FOLD_CHARS,
  FOLD_LINES,
  countLines,
  expand,
  referenceOf,
  removeReference,
  shouldFold,
} from './pasted-text';

/**
 * Le texte collé, replié (F-146 / SF-146-01).
 *
 * <p>Le test qui compte est {@link #unereferenceEffaceeNenvoieRien} : ce qui part doit être ce que
 * l'utilisateur a sous les yeux. Un texte réinjecté après qu'il a effacé sa référence serait un
 * envoi qu'il n'a pas voulu.</p>
 */
describe('pasted-text', () => {
  const long = Array.from({ length: FOLD_LINES + 2 }, (_, i) => `ligne ${i}`).join('\n');

  it('replie un collage long, laisse passer un collage court', () => {
    expect(shouldFold(long)).toBeTrue();
    expect(shouldFold('x'.repeat(FOLD_CHARS + 1))).toBeTrue();
    // Ce qui tient dans le champ y reste : on ne replie pas pour le principe.
    expect(shouldFold('deux\nlignes')).toBeFalse();
    expect(shouldFold('')).toBeFalse();
  });

  it('compte les lignes, c\'est ce que la puce annonce', () => {
    expect(countLines(long)).toBe(FOLD_LINES + 2);
    expect(countLines('une seule')).toBe(1);
  });

  it('LE CRITÈRE : le texte intégral reprend la place de sa référence', () => {
    const draft = `analyse ceci ${referenceOf(1)} et dis-moi`;

    expect(expand(draft, [{ index: 1, text: long, lines: countLines(long) }]))
      .toBe(`analyse ceci ${long} et dis-moi`);
  });

  it('une référence EFFACÉE n\'envoie rien', () => {
    // La garantie de non-surprise : le champ fait foi.
    const draft = 'finalement je décris moi-même';

    expect(expand(draft, [{ index: 1, text: long, lines: 7 }])).toBe(draft);
  });

  it('deux collages restent distincts', () => {
    const draft = `${referenceOf(1)} puis ${referenceOf(2)}`;

    expect(expand(draft, [
      { index: 1, text: 'PREMIER', lines: 1 },
      { index: 2, text: 'SECOND', lines: 1 },
    ])).toBe('PREMIER puis SECOND');
  });

  it('une référence répétée à la main est remplacée partout : le champ fait foi', () => {
    const draft = `${referenceOf(1)} et encore ${referenceOf(1)}`;

    expect(expand(draft, [{ index: 1, text: 'TEXTE', lines: 1 }])).toBe('TEXTE et encore TEXTE');
  });

  it('retirer une référence la sort du champ sans laisser de trou', () => {
    expect(removeReference(`avant ${referenceOf(1)} après`, 1)).toBe('avant après');
    expect(removeReference(referenceOf(1), 1)).toBe('');
  });

  it('ne casse rien sur des entrées vides', () => {
    expect(expand('', [])).toBe('');
    expect(expand(null as unknown as string, [])).toBe('');
    expect(removeReference(null as unknown as string, 1)).toBe('');
  });
});
