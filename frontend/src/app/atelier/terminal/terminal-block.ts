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
