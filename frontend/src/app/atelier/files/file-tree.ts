/**
 * Utilitaire pur de construction d'arborescence pour l'explorateur de fichiers de l'Atelier
 * (SF-28-15). Le backend renvoie une **liste plate** de chemins relatifs (`src/a.js`,
 * `src/utils/b.js`, `bibliotheque/c.md`, `CLAUDE.md`) ; l'UI a besoin d'un arbre imbriqué de
 * dossiers repliables. Fonction sans dépendance Angular → testable unitairement.
 */

/** Nœud de l'arbre : un dossier (avec enfants) ou un fichier (feuille). */
export interface TreeNode {
  /** Nom court du segment (dernière composante du chemin). */
  name: string;
  /** Chemin relatif complet depuis la racine du workspace. */
  path: string;
  type: 'folder' | 'file';
  /** Enfants d'un dossier (absent pour un fichier). */
  children?: TreeNode[];
}

/** Dossier interne mutable pendant la construction (indexé par nom d'enfant pour l'insertion). */
interface FolderBuild {
  node: TreeNode;
  childIndex: Map<string, FolderBuild>;
}

/**
 * Transforme une liste de chemins plats en arbre imbriqué. Les segments intermédiaires deviennent
 * des dossiers ; le dernier segment, un fichier. Les segments `.gitkeep` (marqueur de dossier vide,
 * SF-28-15) créent le dossier parent sans y ajouter de fichier visible. À chaque niveau, les
 * **dossiers précèdent les fichiers**, puis tri alphabétique (insensible à la casse, locale).
 *
 * @param paths chemins relatifs (séparateur `/`) ; entrées vides ou en double sont ignorées.
 * @return les nœuds racine triés.
 */
export function buildTree(paths: string[]): TreeNode[] {
  const root: FolderBuild = {
    node: { name: '', path: '', type: 'folder', children: [] },
    childIndex: new Map(),
  };

  for (const raw of paths) {
    if (!raw) {
      continue;
    }
    const segments = raw.split('/').filter((s) => s.length > 0);
    if (segments.length === 0) {
      continue;
    }
    let current = root;
    let prefix = '';
    for (let i = 0; i < segments.length; i++) {
      const segment = segments[i];
      prefix = prefix ? `${prefix}/${segment}` : segment;
      const isLeaf = i === segments.length - 1;

      // `.gitkeep` : marqueur de dossier (SF-28-15). Le parent existe déjà : on n'ajoute pas de feuille.
      if (isLeaf && segment === '.gitkeep') {
        break;
      }

      if (isLeaf) {
        if (!current.childIndex.has(segment)) {
          const fileNode: TreeNode = { name: segment, path: prefix, type: 'file' };
          current.childIndex.set(segment, { node: fileNode, childIndex: new Map() });
          current.node.children!.push(fileNode);
        }
        break;
      }

      let child = current.childIndex.get(segment);
      if (!child || child.node.type !== 'folder') {
        const folderNode: TreeNode = { name: segment, path: prefix, type: 'folder', children: [] };
        child = { node: folderNode, childIndex: new Map() };
        current.childIndex.set(segment, child);
        current.node.children!.push(folderNode);
      }
      current = child;
    }
  }

  sortNodes(root.node.children!);
  return root.node.children!;
}

/** Trie récursivement : dossiers avant fichiers, puis ordre alphabétique insensible à la casse. */
function sortNodes(nodes: TreeNode[]): void {
  nodes.sort((a, b) => {
    if (a.type !== b.type) {
      return a.type === 'folder' ? -1 : 1;
    }
    return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  });
  for (const node of nodes) {
    if (node.children) {
      sortNodes(node.children);
    }
  }
}
