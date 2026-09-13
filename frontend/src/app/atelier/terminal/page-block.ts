import { AtelierTerminalBlock, AtelierTerminalPage } from '../../core/models/atelier.models';

/**
 * **Le bloc « Page publiée »** (F-109 / SF-109-03), en fonctions pures.
 */

/** Le titre du bloc : « Page publiée — Maquette de la Forge ». */
export function pageHeadline(page: AtelierTerminalPage): string {
  return `Page publiée — ${page.title}`;
}

/** L'adresse du plein écran, dans l'application. */
export function pageViewerPath(pageId: string): string {
  return `/pages/${encodeURIComponent(pageId)}`;
}

/**
 * **Le bloc de transcription d'une page reçue au fil de l'eau.** Son `toolUseId` est celui de l'appel ; il est
 * rangé comme une carte (`withCards`) pour survivre au recalcul des blocs vivants.
 */
export function pageBlock(toolUseId: string, page: AtelierTerminalPage): AtelierTerminalBlock {
  return {
    tool: 'page_publish',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    page,
  };
}
