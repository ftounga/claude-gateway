import { AtelierFileDiff } from '../../core/models/atelier.models';

/**
 * Modification d'un fichier telle qu'affichée dans le fil (F-37 / SF-37-02) : les données du backend,
 * plus l'état de repli, qui appartient à l'écran et à personne d'autre.
 */
export interface AtelierFileDiffView extends AtelierFileDiff {
  /** Le diff de **ce** fichier est déplié. Replié par défaut : le diff complète le fil, il ne le noie pas. */
  expanded: boolean;
}

/** Nature d'une ligne de diff, qui décide de son style. */
export type DiffLineType = 'add' | 'remove' | 'hunk' | 'context';

/** Une ligne de diff prête à afficher. */
export interface DiffLine {
  type: DiffLineType;
  text: string;
}

/**
 * Convertit les modifications reçues du backend en vues repliées.
 *
 * Défensif de bout en bout : une entrée sans chemin ne désigne rien à l'écran et est écartée, et tout
 * champ absent retombe sur sa valeur neutre. Un défaut d'affichage ne doit jamais faire perdre le
 * reste du tour.
 */
export function toDiffViews(diffs: AtelierFileDiff[] | undefined | null): AtelierFileDiffView[] {
  if (!Array.isArray(diffs)) {
    return [];
  }
  return diffs
    .filter((entry): entry is AtelierFileDiff => !!entry && typeof entry.path === 'string' && entry.path.length > 0)
    .map((entry) => ({
      path: entry.path,
      added: entry.added === true,
      diff: typeof entry.diff === 'string' ? entry.diff : '',
      addedLines: count(entry.addedLines),
      removedLines: count(entry.removedLines),
      omittedLines: count(entry.omittedLines),
      unreadable: entry.unreadable === true,
      expanded: false,
    }));
}

/** Compteur défensif : une valeur absente ou non numérique vaut zéro, jamais `NaN` à l'écran. */
function count(value: number | undefined | null): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

/**
 * Découpe un diff unifié en lignes typées. Le préfixe de chaque ligne suffit à la classer — c'est
 * tout ce que le format unifié garantit, et c'est tout ce dont l'affichage a besoin.
 */
export function diffLines(view: AtelierFileDiffView): DiffLine[] {
  if (!view.diff) {
    return [];
  }
  return view.diff.split('\n').map((text) => ({ type: lineType(text), text }));
}

function lineType(text: string): DiffLineType {
  if (text.startsWith('@@')) {
    return 'hunk';
  }
  if (text.startsWith('+')) {
    return 'add';
  }
  if (text.startsWith('-')) {
    return 'remove';
  }
  return 'context';
}

/**
 * Compteur d'un fichier : « +3 −1 ». Un compteur à zéro est omis — « +0 » sur une suppression pure
 * serait du bruit. Renvoie une chaîne vide quand rien n'est comptable (fichier illisible).
 */
export function diffCountLabel(view: AtelierFileDiffView): string {
  const parts: string[] = [];
  if (view.addedLines > 0) {
    parts.push(`+${view.addedLines}`);
  }
  if (view.removedLines > 0) {
    parts.push(`−${view.removedLines}`);
  }
  return parts.join(' ');
}

/**
 * Mention du volume omis par la borne du backend, ou chaîne vide quand le diff est complet. Le dire
 * est nécessaire : un diff tronqué en silence laisserait croire que rien d'autre n'a changé.
 */
export function omittedLabel(view: AtelierFileDiffView): string {
  if (view.omittedLines <= 0) {
    return '';
  }
  return view.omittedLines === 1 ? '1 ligne omise' : `${view.omittedLines} lignes omises`;
}
