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

/** Libellé du fil principal — celui du coordinateur, seul fil d'un run sans délégation. */
export const MAIN_THREAD_LABEL = 'Fil principal';

/**
 * Bloc prêt à l'affichage, accompagné de sa provenance (F-35 SF-35-03).
 *
 * `block` référence l'objet d'origine : le replier/déplier depuis la vue continue de le muter.
 */
export interface TerminalRow {
  block: AtelierTerminalBlock;
  /**
   * Libellé du fil à afficher **au-dessus** du bloc, ou `null` quand il n'y a rien à dire — soit
   * parce que le run n'a pas délégué du tout, soit parce que le bloc prolonge le fil précédent.
   */
  header: string | null;
  /** Vrai si le bloc vient d'une sous-tâche déléguée : il porte alors le rail d'accent. */
  subtask: boolean;
}

/**
 * Numérote les fils délégués d'un tour **dans l'ordre de leur première apparition**, et décide où
 * placer un libellé (F-35 SF-35-03).
 *
 * <p>Le libellé n'apparaît qu'à la **frontière** entre deux fils : une sous-tâche qui enchaîne
 * quatre commandes n'est annoncée qu'une fois. Sans délégation — le cas courant — aucun bloc ne
 * porte de fil, donc aucun libellé n'est produit et le rendu est celui d'avant F-35, à l'identique.</p>
 *
 * <p>La numérotation est **locale au tour** : l'identifiant du fournisseur est une chaîne opaque sans
 * continuité garantie d'un tour à l'autre, et l'unité de lecture est le tour.</p>
 */
export function terminalRows(blocks: AtelierTerminalBlock[]): TerminalRow[] {
  const delegated = blocks.some((block) => block.threadId !== null);
  const numbers = new Map<string, number>();
  let previous: string | null | undefined = undefined;
  return blocks.map((block) => {
    const thread = block.threadId;
    if (thread !== null && !numbers.has(thread)) {
      numbers.set(thread, numbers.size + 1);
    }
    const changed = previous === undefined || previous !== thread;
    previous = thread;
    return {
      block,
      header:
        !delegated || !changed
          ? null
          : thread === null
            ? MAIN_THREAD_LABEL
            : `Sous-tâche ${numbers.get(thread)}`,
      subtask: thread !== null,
    };
  });
}

/** Nombre de sous-tâches **distinctes** ouvertes par un tour ; `0` sans délégation. */
export function subtaskCount(blocks: AtelierTerminalBlock[]): number {
  const threads = new Set<string>();
  for (const block of blocks) {
    if (block.threadId !== null) {
      threads.add(block.threadId);
    }
  }
  return threads.size;
}

/** Durée en secondes → `m:ss` (F-30 SF-30-02). */
export function formatElapsed(totalSeconds: number): string {
  const seconds = totalSeconds % 60;
  return `${Math.floor(totalSeconds / 60)}:${seconds < 10 ? '0' : ''}${seconds}`;
}
