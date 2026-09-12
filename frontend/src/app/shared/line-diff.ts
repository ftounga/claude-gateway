/**
 * **Différentiel ligne à ligne** (F-75 / SF-75-03).
 *
 * Le dépôt d'un paquet de gouvernance est idempotent : il n'écrase **jamais** un fichier déjà
 * présent. Quand le fichier existe, ce n'est donc pas celui du paquet qui s'appliquera — c'est celui
 * qui est en place. Montrer les deux côte à côte est la seule façon de savoir ce qu'on accepte
 * **avant** d'accepter.
 *
 * **Calculé à l'écran, et non au serveur** : la gateway rend deux contenus, qui suffisent. Un diff
 * calculé côté serveur serait une seconde représentation à tenir, et un aller-retour de plus à
 * chaque fichier ouvert.
 *
 * L'algorithme est la plus longue sous-séquence commune, en programmation dynamique. Il est
 * **borné** : au-delà de `MAX_DIFF_LINES` lignes de part et d'autre, la table deviendrait plus
 * coûteuse que le service rendu, et on rend alors un différentiel « tout remplacé », honnête et
 * immédiat.
 */

/** Ce qui arrive à une ligne. */
export type LineChange = 'same' | 'added' | 'removed';

/**
 * Une ligne du différentiel.
 *
 * @property change   `removed` = présent dans l'existant seul, `added` = apporté par le paquet seul
 * @property text     le texte de la ligne, tel quel
 * @property leftNo   numéro de ligne côté existant, ou `null`
 * @property rightNo  numéro de ligne côté paquet, ou `null`
 */
export interface DiffLine {
  readonly change: LineChange;
  readonly text: string;
  readonly leftNo: number | null;
  readonly rightNo: number | null;
}

/** Au-delà, on ne construit pas la table : le coût dépasserait le service rendu. */
export const MAX_DIFF_LINES = 2000;

/**
 * Différentiel entre l'existant (`left`) et ce que le paquet apporte (`right`).
 *
 * Deux contenus identiques rendent une liste entièrement `same` — et l'écran peut alors dire
 * « rien ne changerait » plutôt que d'afficher un diff vide et muet.
 */
export function lineDiff(left: string, right: string): DiffLine[] {
  const a = splitLines(left);
  const b = splitLines(right);

  if (a.length > MAX_DIFF_LINES || b.length > MAX_DIFF_LINES) {
    // Trop long pour une table : on rend un différentiel grossier mais VRAI — tout l'existant d'un
    // côté, tout l'apport de l'autre. Mieux qu'une attente, et jamais un mensonge.
    return [
      ...a.map((text, index) => line('removed', text, index + 1, null)),
      ...b.map((text, index) => line('added', text, null, index + 1)),
    ];
  }

  // Table des longueurs de la plus longue sous-séquence commune.
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  );
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }

  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push(line('same', a[i], i + 1, j + 1));
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push(line('removed', a[i], i + 1, null));
      i++;
    } else {
      out.push(line('added', b[j], null, j + 1));
      j++;
    }
  }
  while (i < a.length) {
    out.push(line('removed', a[i], i + 1, null));
    i++;
  }
  while (j < b.length) {
    out.push(line('added', b[j], null, j + 1));
    j++;
  }
  return out;
}

/** Vrai si le différentiel ne contient aucun changement — les deux contenus sont identiques. */
export function isUnchanged(diff: readonly DiffLine[]): boolean {
  return diff.every((entry) => entry.change === 'same');
}

function line(
  change: LineChange,
  text: string,
  leftNo: number | null,
  rightNo: number | null,
): DiffLine {
  return { change, text, leftNo, rightNo };
}

/**
 * Découpe en lignes, fins de ligne Windows comprises.
 *
 * Une chaîne vide donne **aucune** ligne — et non une ligne vide : un fichier absent ne doit pas
 * apparaître comme un fichier contenant une ligne blanche.
 */
function splitLines(content: string): string[] {
  if (content.length === 0) {
    return [];
  }
  return content.replace(/\r\n/g, '\n').split('\n');
}
