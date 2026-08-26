import { AtelierFileDiff } from '../../core/models/atelier.models';
import {
  AtelierFileDiffView,
  diffCountLabel,
  diffLines,
  omittedLabel,
  toDiffViews,
} from './terminal-diff';

/**
 * Découpage et libellés des modifications d'un tour (F-37 SF-37-02). Le diff est calculé et borné
 * par le backend : ici on ne fait que le lire — et lire défensivement, pour qu'un champ manquant
 * n'efface jamais le reste du tour.
 */
describe('terminal-diff — modifications du tour (F-37)', () => {
  const raw = (over: Partial<AtelierFileDiff> = {}): AtelierFileDiff => ({
    path: 'src/app.ts',
    added: false,
    diff: '@@ -1,2 +1,2 @@\n un\n-deux\n+DEUX',
    addedLines: 1,
    removedLines: 1,
    omittedLines: 0,
    unreadable: false,
    ...over,
  });

  const view = (over: Partial<AtelierFileDiffView> = {}): AtelierFileDiffView => ({
    ...raw(),
    expanded: false,
    ...over,
  });

  describe('toDiffViews', () => {
    it('replie chaque fichier par défaut', () => {
      const views = toDiffViews([raw()]);

      expect(views.length).toBe(1);
      expect(views[0].expanded).toBeFalse();
      expect(views[0].path).toBe('src/app.ts');
    });

    it('rend une liste vide quand le backend n’envoie rien (client antérieur à F-37)', () => {
      expect(toDiffViews(undefined)).toEqual([]);
      expect(toDiffViews(null)).toEqual([]);
      expect(toDiffViews([])).toEqual([]);
    });

    it('écarte une entrée sans chemin : elle ne désignerait rien à l’écran', () => {
      const views = toDiffViews([
        raw(),
        { ...raw(), path: '' },
        { path: undefined } as unknown as AtelierFileDiff,
      ]);

      expect(views.length).toBe(1);
    });

    it('ramène tout champ absent ou aberrant à sa valeur neutre', () => {
      const views = toDiffViews([
        {
          path: 'src/x.ts',
          addedLines: Number.NaN,
          removedLines: -4,
        } as unknown as AtelierFileDiff,
      ]);

      expect(views[0].diff).toBe('');
      expect(views[0].added).toBeFalse();
      expect(views[0].unreadable).toBeFalse();
      expect(views[0].addedLines).toBe(0);
      expect(views[0].removedLines).toBe(0);
      expect(views[0].omittedLines).toBe(0);
    });
  });

  describe('diffLines', () => {
    it('classe chaque ligne par son préfixe', () => {
      expect(diffLines(view())).toEqual([
        { type: 'hunk', text: '@@ -1,2 +1,2 @@' },
        { type: 'context', text: ' un' },
        { type: 'remove', text: '-deux' },
        { type: 'add', text: '+DEUX' },
      ]);
    });

    it('ne rend aucune ligne pour un diff vide', () => {
      expect(diffLines(view({ diff: '' }))).toEqual([]);
    });

    it('traite une ligne vide comme du contexte, jamais comme une erreur', () => {
      expect(diffLines(view({ diff: '@@ -1,1 +1,1 @@\n' }))).toEqual([
        { type: 'hunk', text: '@@ -1,1 +1,1 @@' },
        { type: 'context', text: '' },
      ]);
    });
  });

  describe('diffCountLabel', () => {
    it('affiche les ajouts et les retraits', () => {
      expect(diffCountLabel(view({ addedLines: 3, removedLines: 1 }))).toBe('+3 −1');
    });

    it('omet un compteur à zéro : « +0 » sur une suppression pure serait du bruit', () => {
      expect(diffCountLabel(view({ addedLines: 0, removedLines: 2 }))).toBe('−2');
      expect(diffCountLabel(view({ addedLines: 5, removedLines: 0 }))).toBe('+5');
    });

    it('ne dit rien quand rien n’est comptable', () => {
      expect(diffCountLabel(view({ addedLines: 0, removedLines: 0 }))).toBe('');
    });
  });

  describe('omittedLabel', () => {
    it('ne dit rien quand le diff est complet', () => {
      expect(omittedLabel(view({ omittedLines: 0 }))).toBe('');
    });

    it('accorde le singulier et le pluriel', () => {
      expect(omittedLabel(view({ omittedLines: 1 }))).toBe('1 ligne omise');
      expect(omittedLabel(view({ omittedLines: 42 }))).toBe('42 lignes omises');
    });
  });
});
