import { AtelierTerminalBlock } from '../../core/models/atelier.models';

/**
 * **Les marqueurs de flux** (F-162 / SF-162-03), en fonctions pures : une trace discrète et
 * persistante, dans le fil du tour, de ce que la passerelle a fait pour tenir le contexte —
 * « Conversation compactée · N tours résumés » et « Détail rappelé · tour N ».
 *
 * <p>Ils sont rangés comme les cartes (`withCards`) : un bloc de transcription à part entière,
 * reconnaissable à son champ `marker`, qui survit au recalcul des blocs vivants et se retrouve dans
 * la transcription conservée du tour (`appendTurnReply`). Ce ne sont ni des sorties de commande ni des
 * cartes — l'écran les rend sur une ligne dédiée, sans le shell `$`.</p>
 */

/** Le libellé du marqueur de compaction : « Conversation compactée · 12 tours résumés ». */
export function compactionMarkerLabel(summarizedTurns: number): string {
  const n = Math.max(0, Math.trunc(summarizedTurns));
  const tours = n > 1 ? `${n} tours résumés` : `${n} tour résumé`;
  return `Conversation compactée · ${tours}`;
}

/**
 * Le bloc marqueur « Conversation compactée · N tours résumés ». Son `toolUseId` est stable pour le
 * tour (`compaction`) : la compaction est unique au démarrage du tour.
 */
export function compactionMarkerBlock(summarizedTurns: number): AtelierTerminalBlock {
  return {
    tool: 'compaction',
    toolUseId: 'compaction',
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    marker: { kind: 'compaction', label: compactionMarkerLabel(summarizedTurns) },
  };
}

/** Le bloc marqueur « Détail rappelé · tour N » (le repère arrive déjà mis en forme du backend). */
export function recallMarkerBlock(repere: string): AtelierTerminalBlock {
  return {
    tool: 'recall',
    toolUseId: `recall-${repere}`,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    marker: { kind: 'recall', label: `Détail rappelé · ${repere}` },
  };
}
