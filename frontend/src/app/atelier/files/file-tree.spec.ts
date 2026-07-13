import { TreeNode, buildTree } from './file-tree';

/** Raccourci : noms des enfants d'un nœud (dans l'ordre de tri). */
function childNames(node: TreeNode): string[] {
  return (node.children ?? []).map((c) => c.name);
}

describe('buildTree', () => {
  it('imbrique correctement des chemins plats en dossiers + fichiers', () => {
    const nodes = buildTree(['src/a.js', 'src/utils/b.js', 'bibliotheque/c.md', 'CLAUDE.md']);

    // Racine : 2 dossiers (bibliotheque, src) avant 1 fichier (CLAUDE.md).
    expect(nodes.map((n) => `${n.type}:${n.name}`)).toEqual([
      'folder:bibliotheque',
      'folder:src',
      'file:CLAUDE.md',
    ]);

    const src = nodes.find((n) => n.name === 'src')!;
    // src : dossier utils avant fichier a.js.
    expect(childNames(src)).toEqual(['utils', 'a.js']);
    expect(src.children!.find((c) => c.name === 'utils')!.children!.map((c) => c.path)).toEqual([
      'src/utils/b.js',
    ]);

    const bib = nodes.find((n) => n.name === 'bibliotheque')!;
    expect(bib.children!.map((c) => c.path)).toEqual(['bibliotheque/c.md']);
  });

  it('place les dossiers avant les fichiers puis trie par ordre alphabétique', () => {
    const nodes = buildTree(['z.txt', 'a.txt', 'beta/x.txt', 'alpha/y.txt']);
    expect(nodes.map((n) => `${n.type}:${n.name}`)).toEqual([
      'folder:alpha',
      'folder:beta',
      'file:a.txt',
      'file:z.txt',
    ]);
  });

  it('gère les fichiers à la racine (cas racine)', () => {
    const nodes = buildTree(['README.md']);
    expect(nodes.length).toBe(1);
    expect(nodes[0]).toEqual({ name: 'README.md', path: 'README.md', type: 'file' });
  });

  it('crée un dossier vide à partir du marqueur .gitkeep sans y ajouter de fichier', () => {
    const nodes = buildTree(['docs/.gitkeep']);
    expect(nodes.length).toBe(1);
    expect(nodes[0].type).toBe('folder');
    expect(nodes[0].name).toBe('docs');
    // Le marqueur .gitkeep n'apparaît pas comme fichier.
    expect(nodes[0].children).toEqual([]);
  });

  it('fusionne plusieurs fichiers sous un même dossier sans le dupliquer', () => {
    const nodes = buildTree(['src/a.js', 'src/b.js']);
    expect(nodes.length).toBe(1);
    expect(childNames(nodes[0])).toEqual(['a.js', 'b.js']);
  });

  it('ignore les entrées vides', () => {
    const nodes = buildTree(['', 'a.txt', '']);
    expect(nodes.map((n) => n.name)).toEqual(['a.txt']);
  });

  it('renvoie un tableau vide pour une liste vide', () => {
    expect(buildTree([])).toEqual([]);
  });
});
