import { AtelierTerminalBlock } from '../../core/models/atelier.models';

/** Seuil de repli d'une sortie longue (F-30 SF-30-02) : au-delà, on masque et on laisse déplier. */
export const TERMINAL_COLLAPSE_LINES = 20;

/**
 * Sortie effectivement affichée : repliée au-delà de {@link TERMINAL_COLLAPSE_LINES} lignes tant que
 * l'utilisateur ne l'a pas dépliée. La sortie est déjà bornée côté backend (SF-30-01) : ici on ne
 * perd rien, on masque.
 */
export function visibleOutput(block: AtelierTerminalBlock): string {
  if (block.expanded) {
    return block.output;
  }
  const lines = block.output.split('\n');
  return lines.length <= TERMINAL_COLLAPSE_LINES
    ? block.output
    : lines.slice(0, TERMINAL_COLLAPSE_LINES).join('\n');
}

/** Nombre de lignes masquées par le repli ; `0` si la sortie tient sous le seuil. */
export function hiddenLineCount(block: AtelierTerminalBlock): number {
  return Math.max(0, block.output.split('\n').length - TERMINAL_COLLAPSE_LINES);
}

/** En-tête d'un bloc : la commande si connue, sinon le nom de l'outil (bloc orphelin). */
export function blockLabel(block: AtelierTerminalBlock): string {
  return block.command ?? block.tool;
}

/** Durée en secondes → `m:ss` (F-30 SF-30-02). */
export function formatElapsed(totalSeconds: number): string {
  const seconds = totalSeconds % 60;
  return `${Math.floor(totalSeconds / 60)}:${seconds < 10 ? '0' : ''}${seconds}`;
}

/**
 * Numérote les fils d'un tour (F-35 SF-35-03). Le **premier** fil rencontré est le coordinateur : il
 * est absent de la table, ses blocs ne portent aucun badge — c'est le cas de tous les runs
 * séquentiels, dont l'affichage ne change donc pas. Chaque autre fil reçoit son rang d'apparition
 * (`1`, `2`, `3`…).
 *
 * Il n'existe aucun marqueur disant « ce fil est le principal » : l'ordre d'apparition est le seul
 * signal disponible, et il est fiable — c'est le coordinateur qui reçoit le message de l'utilisateur
 * et qui délègue, donc lui qui agit en premier.
 */
export function subtaskIndexes(blocks: AtelierTerminalBlock[]): Map<string, number> {
  const indexes = new Map<string, number>();
  let coordinator: string | null = null;
  let next = 1;
  for (const block of blocks) {
    const thread = block.threadId;
    if (!thread) {
      continue;
    }
    if (coordinator === null) {
      coordinator = thread;
      continue;
    }
    if (thread !== coordinator && !indexes.has(thread)) {
      indexes.set(thread, next);
      next += 1;
    }
  }
  return indexes;
}

/**
 * Libellé de sous-tâche d'un bloc, ou `null` quand il appartient au travail principal (coordinateur,
 * ou run sans aucun fil). Le libellé est court et en minuscules, comme le reste de la vue terminal :
 * il rend la lecture possible sans transformer le flux en tableau de bord.
 */
export function subtaskLabel(
  block: AtelierTerminalBlock,
  indexes: Map<string, number>,
): string | null {
  const index = block.threadId ? indexes.get(block.threadId) : undefined;
  return index === undefined ? null : `sous-tâche ${index}`;
}
