import { AtelierTerminalBlock } from '../../core/models/atelier.models';

/**
 * Rendu terminal des **sous-agents en action** (F-150 / SF-150-06) : les explorations parallèles
 * d'un tour (`explore`, lecture seule, SF-39-21) et la sous-tâche écrivaine (`task`, SF-150-02).
 *
 * <p>Ce module est une **fonction pure** — il ne dépend que des blocs de transcription déjà bâtis
 * (`chat-steps.ts` en direct, transcription relue en historique). Il ne change **rien** à
 * l'exécution : il ne fait que **restituer** ce qui a déjà eu lieu. Le regroupement se lit de la
 * transcription elle-même : la boucle maison émet les `explore` d'un lot **consécutivement** (le lot
 * est exécuté ensemble par `exploreConcurrently` avant la boucle d'attachement), donc un run de blocs
 * `explore` **adjacents** EST le lot parallèle — aucun identifiant de groupe backend n'est requis.</p>
 */

/** Un bloc est une exploration déléguée (sous-agent lecture seule). */
export function isExploreBlock(block: AtelierTerminalBlock): boolean {
  return block.tool === 'explore';
}

/** Un bloc est une sous-tâche écrivaine (sous-agent `task`, F-150). */
export function isTaskBlock(block: AtelierTerminalBlock): boolean {
  return block.tool === 'task';
}

/**
 * La **question** d'un sous-agent d'exploration, telle qu'on veut la lire dans le bloc groupé.
 *
 * <p>En direct, l'en-tête vaut « exploration « q » » (SF-39-08) : on retire l'habillage pour ne
 * garder que `q`. En historique, la consigne relue vaut déjà `q` (SF-150-06 backend). Repli propre
 * sur le libellé brut, puis sur le type — jamais une chaîne vide déguisée en question.</p>
 */
export function subAgentQuestion(block: AtelierTerminalBlock): string {
  const raw = (block.command ?? '').trim();
  const wrapped = raw.match(/^exploration\s+«\s*(.*?)\s*»$/u);
  if (wrapped) {
    return wrapped[1];
  }
  return raw.length > 0 ? raw : block.tool;
}

/** Longueur minimale d'un lot pour être regroupé : un `explore` isolé garde son rendu ligne. */
const MIN_GROUP = 2;

/** Un lot d'explorations parallèles, tel que le bloc groupé le restitue. */
export interface SubAgentExploreGroup {
  /** Les questions du lot, dans l'ordre d'apparition. Sa longueur est le « N » du titre. */
  questions: string[];
}

/**
 * Début, à `index`, du run d'`explore` **adjacents** qui contient ce bloc — ou `index` lui-même s'il
 * n'en est pas un.
 */
function runStart(blocks: AtelierTerminalBlock[], index: number): number {
  let start = index;
  while (start > 0 && isExploreBlock(blocks[start - 1])) {
    start -= 1;
  }
  return start;
}

/** Longueur du run d'`explore` adjacents commençant à `start` (0 si `start` n'est pas un `explore`). */
function runLength(blocks: AtelierTerminalBlock[], start: number): number {
  let length = 0;
  for (let i = start; i < blocks.length && isExploreBlock(blocks[i]); i += 1) {
    length += 1;
  }
  return length;
}

/**
 * Si le bloc à `index` **ouvre** un lot d'explorations parallèles (≥ {@link MIN_GROUP} `explore`
 * adjacents), rend le lot (ses questions dans l'ordre) ; sinon `null`. C'est le seul point qui décide
 * du regroupement : le gabarit rend le lot sur son ouverture, et masque ses autres membres via
 * {@link isGroupedExploreMember}.
 */
export function exploreGroupAt(
  blocks: AtelierTerminalBlock[],
  index: number,
): SubAgentExploreGroup | null {
  const block = blocks[index];
  if (!block || !isExploreBlock(block)) {
    return null;
  }
  // N'ouvrir un lot que sur le PREMIER `explore` du run : les suivants sont des membres.
  if (index > 0 && isExploreBlock(blocks[index - 1])) {
    return null;
  }
  const length = runLength(blocks, index);
  if (length < MIN_GROUP) {
    return null;
  }
  const questions: string[] = [];
  for (let i = index; i < index + length; i += 1) {
    questions.push(subAgentQuestion(blocks[i]));
  }
  return { questions };
}

/**
 * Vrai si le bloc à `index` est un **membre non-ouvrant** d'un lot regroupé (run de ≥
 * {@link MIN_GROUP} `explore` adjacents) : le gabarit le **masque**, parce que son ouverture a déjà
 * rendu tout le lot. Un `explore` isolé n'est jamais un membre — son rendu ligne est préservé.
 */
export function isGroupedExploreMember(blocks: AtelierTerminalBlock[], index: number): boolean {
  const block = blocks[index];
  if (!block || !isExploreBlock(block)) {
    return false;
  }
  const start = runStart(blocks, index);
  return runLength(blocks, start) >= MIN_GROUP && index !== start;
}
