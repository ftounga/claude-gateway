import { AtelierMessage, AtelierTerminalBlock } from '../core/models/atelier.models';
import { normalizeTurnQuestions } from '../core/services/turn-questions';
import { AtelierThreadItem } from './atelier.types';
import { toDiffViews } from './terminal/terminal-diff';

/**
 * Convertit un message d'historique en tour du fil (F-30 SF-30-09). Une transcription absente ou
 * illisible produit simplement un tour sans terminal : un défaut d'affichage ne doit jamais empêcher
 * de relire sa conversation.
 */
export function toThreadItem(message: AtelierMessage): AtelierThreadItem {
  const item: AtelierThreadItem = {
    id: message.id,
    role: message.role,
    content: message.content,
    actions: [],
    // F-169 / SF-169-03 : les pièces jointes du message, reconstruites depuis le transcript persistant
    // (SF-169-02), rendues dans la bulle au rechargement. Absent pour un message sans pièce jointe.
    ...(message.files && message.files.length > 0 ? { files: message.files } : {}),
  };
  const stored = message.terminal;
  if (!stored) {
    return item;
  }
  // Le drapeau d'interruption vit hors des blocs : un tour interrompu avant d'avoir lancé la moindre
  // commande n'a pas de transcription, et doit pourtant rester marqué au rechargement (F-32).
  item.interrupted = stored.interrupted === true;
  // Même raison pour le plafond de dépense (F-36 SF-36-04) : sans cela, la mention disparaîtrait au
  // rechargement et l'utilisateur relirait un tour d'apparence normale, arrêté sans motif.
  item.budgetReached = stored.budgetReached === true;
  // F-192 / SF-192-02 : les suites proposées par l'agent survivent au rechargement.
  if (Array.isArray(stored.nextSteps) && stored.nextSteps.length > 0) {
    item.nextSteps = stored.nextSteps;
  }
  // F-196 / SF-196-02 : l'avancement du chantier survit lui aussi au rechargement.
  if (stored.nextStepsChantier) {
    item.nextStepsChantier = stored.nextStepsChantier;
  }
  // F-210 / SF-210-03 : les questions d'un tour découpé, et leurs réponses, survivent au rechargement.
  const questions = normalizeTurnQuestions(stored.questions, stored.answers);
  if (questions) {
    item.questions = questions;
  }
  // Les modifications vivent hors des blocs (F-37 SF-37-02) : un tour peut n'avoir lancé aucune
  // commande visible et avoir pourtant réécrit des fichiers.
  const diffs = toDiffViews(stored.diffs);
  if (diffs.length > 0) {
    item.diffs = diffs;
  }
  // Le coût vit lui aussi hors des blocs (F-39 / SF-39-15) : la boucle maison relève sa
  // consommation sans persister de transcription, et un tour mesuré perdait sa mesure ici.
  const tokens = (stored.inputTokens ?? 0) + (stored.outputTokens ?? 0);
  if (tokens > 0) {
    // Le montant (F-133 / SF-133-02) n'est présent dans le relevé relu que pour l'administrateur :
    // la passerelle le retire des autres réponses, il n'est pas filtré ici.
    item.cost = {
      elapsedSeconds: stored.activeSeconds ?? 0,
      tokens,
      ...(stored.costEur ? { amount: stored.costEur } : {}),
    };
  }
  if (!Array.isArray(stored.blocks) || stored.blocks.length === 0) {
    return item;
  }
  item.terminal = stored.blocks.map((block): AtelierTerminalBlock => ({
    tool: block.tool,
    command: block.command ?? undefined,
    toolUseId: block.toolUseId ?? null,
    threadId: block.threadId ?? null,
    output: block.output ?? '',
    hasOutput: block.hasOutput === true,
    error: block.error === true,
    expanded: false,
  }));
  return item;
}

