import { AtelierConfirmRequest } from '../core/models/atelier.models';
import { normalize } from './jarvis-oral';

/**
 * **Confirmer à voix haute** (F-231 / SF-231-04, D4) — fonctions pures, testées seules.
 *
 * Une erreur de transcription ne doit **jamais** déclencher quoi que ce soit : seul un « oui » net (ou « je
 * confirme ») vaut accord ; « non » vaut refus ; tout le reste — silence, phrase ambiguë, « d'accord mais… » —
 * vaut **rien**.
 */
export type ConfirmationAnswer = 'oui' | 'non' | 'doute';

const NEGATIONS = /\b(non|nan|pas|attends|attend|mais|sauf|jamais|annule|stop|arrete)\b/;
const YES = /^(oui|ouais|oui oui|oui vas y|oui c est bon|oui je confirme|oui confirme|oui merci|oui allez|je confirme|confirme|c est confirme)$/;
const NO = /^(non|nan|non merci|non non|surtout pas|pas question|n y touche pas|ne fais rien|annule|je refuse|refuse)$/;

/** Ce que vaut la réponse dite à une demande de confirmation. */
export function classifyConfirmation(said: string | null | undefined): ConfirmationAnswer {
  const text = normalize(said ?? '').replace(/^jarvis\s+/, '').trim();
  if (!text) {
    return 'doute';
  }
  if (NO.test(text)) {
    return 'non';
  }
  if (YES.test(text) && !NEGATIONS.test(text)) {
    return 'oui';
  }
  return 'doute';
}

/** Au-delà, le détail d'une commande est coupé dans la question dite. */
const MAX_DETAIL = 140;

/**
 * La question dite pour une autorisation d'outil (`ask`) : l'action reformulée, puis « Vous confirmez ? » (F-233 /
 * SF-233-03, D-PO-8 : Jarvis vouvoie).
 */
export function confirmationQuestion(request: Pick<AtelierConfirmRequest, 'tool' | 'detail'>): string {
  const detail = shorten(request.detail);
  const tool = (request.tool ?? '').toLowerCase();
  let action: string;
  if (tool === 'bash' || tool === 'run_command' || tool === 'shell') {
    action = detail ? `lancer la commande « ${detail} »` : 'lancer une commande';
  } else if (tool.includes('write') || tool.includes('edit')) {
    action = detail ? `modifier ${detail}` : 'modifier un fichier';
  } else if (tool === 'email_me') {
    action = 'vous envoyer un courriel';
  } else {
    action = detail ? `utiliser ${request.tool} : ${detail}` : `utiliser ${request.tool}`;
  }
  return `Je vais ${action}. Vous confirmez ?`;
}

/** La phrase finit-elle par une demande de confirmation (« Vous confirmez ? », ou l'ancien « Tu confirmes ? ») ? */
export function asksConfirmation(phrase: string | null | undefined): boolean {
  return /(?:vous confirmez|tu confirmes)\s*\?\s*$/i.test((phrase ?? '').trim());
}

function shorten(detail: string | null | undefined): string {
  const flat = (detail ?? '').replace(/\s+/g, ' ').trim();
  if (flat.length <= MAX_DETAIL) {
    return flat;
  }
  const head = flat.slice(0, MAX_DETAIL);
  const space = head.lastIndexOf(' ');
  return `${(space > 40 ? head.slice(0, space) : head).trimEnd()}…`;
}
