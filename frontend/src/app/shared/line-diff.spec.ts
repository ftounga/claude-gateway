import { MAX_DIFF_LINES, isUnchanged, lineDiff } from './line-diff';

/**
 * F-75 / SF-75-03 — le différentiel qu'on lit avant d'accepter.
 *
 * Ce que ces tests protègent : un différentiel **vrai**. Une ligne inchangée ne doit jamais
 * apparaître comme remplacée, et deux contenus identiques doivent pouvoir être annoncés comme tels.
 */
describe('lineDiff', () => {
  it('ne montre aucun changement entre deux contenus identiques', () => {
    const diff = lineDiff('a\nb\nc', 'a\nb\nc');

    expect(isUnchanged(diff)).toBeTrue();
    expect(diff.map((line) => line.text)).toEqual(['a', 'b', 'c']);
  });

  it('signale une ligne ajoutée par le paquet', () => {
    const diff = lineDiff('a\nc', 'a\nb\nc');

    expect(diff.map((line) => [line.change, line.text])).toEqual([
      ['same', 'a'],
      ['added', 'b'],
      ['same', 'c'],
    ]);
    expect(isUnchanged(diff)).toBeFalse();
  });

  it('signale une ligne présente dans l’existant seul', () => {
    const diff = lineDiff('a\nb\nc', 'a\nc');

    expect(diff.map((line) => [line.change, line.text])).toEqual([
      ['same', 'a'],
      ['removed', 'b'],
      ['same', 'c'],
    ]);
  });

  it('numérote chaque côté séparément', () => {
    const diff = lineDiff('a\nb', 'a\nz');

    expect(diff).toEqual([
      { change: 'same', text: 'a', leftNo: 1, rightNo: 1 },
      { change: 'removed', text: 'b', leftNo: 2, rightNo: null },
      { change: 'added', text: 'z', leftNo: null, rightNo: 2 },
    ]);
  });

  it('traite un fichier absent comme vide, et non comme une ligne blanche', () => {
    const diff = lineDiff('', 'a');

    expect(diff).toEqual([{ change: 'added', text: 'a', leftNo: null, rightNo: 1 }]);
  });

  it('ignore la différence de fin de ligne Windows', () => {
    expect(isUnchanged(lineDiff('a\r\nb', 'a\nb'))).toBeTrue();
  });

  it('reste borné : au-delà du plafond, il rend un différentiel grossier mais vrai', () => {
    const huge = Array.from({ length: MAX_DIFF_LINES + 5 }, (_, i) => `l${i}`).join('\n');

    const diff = lineDiff(huge, huge);

    expect(isUnchanged(diff)).toBeFalse();
    expect(diff.length).toBe(2 * (MAX_DIFF_LINES + 5));
  });
});
