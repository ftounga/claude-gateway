import { HttpErrorResponse } from '@angular/common/http';

import { httpErrorMessage } from '../../shared/http-error.util';

/** Longueur maximale d'une nouvelle, alignée sur la gateway. */
export const NEWS_MAX_CHARS = 20_000;

const HEADER = /^\s*(de|from|exp[ée]diteur|envoy[ée]|sent|date|objet|subject|[àa]|to)\s*:/i;

/**
 * **Le texte commence-t-il par un en-tête de courriel ?** (F-104 / SF-104-02) — pour l'annoncer avant
 * l'envoi. La gateway seule fait foi (`RadarPastedMail`) : ici, on regarde seulement si un expéditeur et
 * une ligne de date figurent parmi les premières lignes.
 */
export function looksLikePastedMail(text: string | null | undefined): boolean {
  if (!text) {
    return false;
  }
  const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0).slice(0, 12);
  let from = false;
  let sent = false;
  for (const line of lines) {
    const match = HEADER.exec(line);
    if (!match) {
      continue;
    }
    const name = match[1].toLowerCase();
    if (['de', 'from', 'expéditeur', 'expediteur'].includes(name)) {
      from = true;
    }
    if (['envoyé', 'envoye', 'sent', 'date'].includes(name)) {
      sent = true;
    }
  }
  return from && sent;
}

/** Ce que le composeur dit d'une nouvelle en échec. */
export function newsErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    switch (err.status) {
      case 402:
        return "Votre quota de consommation est atteint : la nouvelle n'a pas été lue.";
      case 503:
        return 'Le fournisseur est momentanément indisponible. Réessayez dans un instant.';
      case 502:
        return "La nouvelle n'a pas pu être lue. Rien n'a été écrit ; réessayez.";
      case 409:
        return "Ce client n'est plus suivi par la Vigie.";
      default:
        break;
    }
  }
  return httpErrorMessage(err, "La nouvelle n'a pas pu être lue. Rien n'a été écrit.");
}

/** Ce qu'on dit d'une annulation en échec. */
export function newsUndoErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse && err.status === 409) {
    return 'Une correction plus récente a redit la même chose : annulez-la d’abord. Rien n’a été annulé.';
  }
  return httpErrorMessage(err, "La nouvelle n'a pas pu être annulée. Rien n'a changé.");
}
