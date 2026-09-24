import {
  MAX_SUGGESTIONS,
  activeMention,
  applyMention,
  buildContextBlock,
  mentionedPaths,
  suggestPaths,
} from './file-mentions';

/**
 * Les @-mentions de fichiers (F-121 / SF-121-24).
 *
 * <p>Les tests qui comptent : {@code mentionedPaths} n'injecte que des chemins CONNUS (jamais une
 * adresse e-mail ni un `@` inconnu), et une référence effacée ne réapparaît pas — ce qui part est ce
 * que l'utilisateur a sous les yeux.</p>
 */
describe('file-mentions', () => {
  const paths = ['src/app/app.ts', 'src/app/util.ts', 'README.md', 'docs/PROJECT.md'];

  describe('activeMention', () => {
    it('repère le jeton @… immédiatement à gauche du curseur', () => {
      const draft = 'regarde @src/app';
      const mention = activeMention(draft, draft.length);
      expect(mention).not.toBeNull();
      expect(mention!.query).toBe('src/app');
      expect(mention!.start).toBe(8);
      expect(mention!.end).toBe(draft.length);
    });

    it('repère un jeton @ vide (juste après le @)', () => {
      const draft = 'ouvre @';
      const mention = activeMention(draft, draft.length);
      expect(mention).not.toBeNull();
      expect(mention!.query).toBe('');
    });

    it('ne déclenche pas sur une adresse e-mail (a@b)', () => {
      const draft = 'ecris a ntounga@gmail.com';
      expect(activeMention(draft, draft.length)).toBeNull();
    });

    it('ne déclenche pas après un espace suivant le jeton', () => {
      const draft = '@README.md et ensuite';
      expect(activeMention(draft, draft.length)).toBeNull();
    });

    it('renvoie null hors de tout jeton', () => {
      expect(activeMention('rien ici', 4)).toBeNull();
      expect(activeMention('', 0)).toBeNull();
    });
  });

  describe('suggestPaths', () => {
    it('filtre par sous-chaîne insensible à la casse', () => {
      expect(suggestPaths(paths, 'UTIL')).toEqual(['src/app/util.ts']);
      expect(suggestPaths(paths, 'src/app')).toEqual(['src/app/app.ts', 'src/app/util.ts']);
    });

    it('rend les premiers chemins pour une requête vide', () => {
      expect(suggestPaths(paths, '')).toEqual(paths);
    });

    it('plafonne le nombre de suggestions', () => {
      const many = Array.from({ length: MAX_SUGGESTIONS + 5 }, (_, i) => `f${i}.ts`);
      expect(suggestPaths(many, 'f').length).toBe(MAX_SUGGESTIONS);
    });
  });

  describe('applyMention', () => {
    it('remplace le jeton par @chemin et pose le curseur après', () => {
      const draft = 'vois @uti là';
      const mention = activeMention('vois @uti', 'vois @uti'.length)!;
      const result = applyMention(draft, mention, 'src/app/util.ts');
      expect(result.draft).toBe('vois @src/app/util.ts  là');
      expect(result.caret).toBe('vois @src/app/util.ts '.length);
    });
  });

  describe('mentionedPaths', () => {
    it('extrait les chemins connus, distincts, en ordre', () => {
      const draft = 'compare @src/app/app.ts et @README.md puis @src/app/app.ts';
      expect(mentionedPaths(draft, paths)).toEqual(['src/app/app.ts', 'README.md']);
    });

    it('ignore un @ inconnu et une adresse e-mail', () => {
      const draft = 'ecris @inconnu.ts à ntounga@gmail.com';
      expect(mentionedPaths(draft, paths)).toEqual([]);
    });

    it('une référence effacée ne réapparaît pas', () => {
      expect(mentionedPaths('plus de mention', paths)).toEqual([]);
    });
  });

  describe('buildContextBlock', () => {
    it('appose un cartouche par fichier, étiqueté par chemin', () => {
      const block = buildContextBlock([
        { path: 'README.md', content: '# Titre' },
      ]);
      expect(block).toContain('Fichiers mentionnés (@)');
      expect(block).toContain('@README.md :');
      expect(block).toContain('# Titre');
    });

    it('marque un fichier illisible sans bloquer', () => {
      const block = buildContextBlock([
        { path: 'src/app/app.ts', content: 'ok' },
        { path: 'secret.env', content: null },
      ]);
      expect(block).toContain('@src/app/app.ts :');
      expect(block).toContain('contenu non lu');
    });

    it('rend une chaîne vide quand il n\'y a rien à apposer', () => {
      expect(buildContextBlock([])).toBe('');
    });
  });
});
