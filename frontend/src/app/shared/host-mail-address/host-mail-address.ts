import { HttpErrorResponse } from '@angular/common/http';

import { HostMailAddress } from '../../core/models/mail.models';
import { httpErrorMessage } from '../http-error.util';

/**
 * **L'adresse de réception, en mots** (F-110 / SF-110-01) : les décisions d'affichage, en fonctions pures.
 */

/** La phrase d'en-tête et, si besoin, l'attention (ambre §12) : le repli se dit toujours. */
export interface MailSentence {
  text: string;
  attention: boolean;
}

/** Ce que dit l'en-tête du client. */
export function mailSentence(view: HostMailAddress): MailSentence {
  if (view.verified && view.address) {
    return { text: `Courriels : ${view.address}`, attention: false };
  }
  const account = view.accountEmail ?? "l'adresse du compte";
  if (view.codePending && view.address) {
    return {
      text: `Courriels : code envoyé à ${view.address}, à confirmer — en attendant, envoi à ${account}`,
      attention: true,
    };
  }
  return { text: `Courriels : aucune adresse vérifiée — envoi à l'adresse du compte ${account}`, attention: false };
}

/** Une adresse plausible : la vérification par code prouve le reste. */
export function isPlausibleAddress(value: string): boolean {
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.length <= 254
    && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed);
}

/** Exactement six chiffres. */
export function isValidCode(value: string): boolean {
  return /^\d{6}$/.test(value.trim());
}

/** Le message d'un refus de la gateway, lisible. */
export function mailErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 400 || err.status === 409 || err.status === 429 || err.status === 502) {
      return httpErrorMessage(err, "L'opération n'a pas abouti.");
    }
    if (err.status === 403 || err.status === 404) {
      return "Le réglage n'est pas disponible pour ce client.";
    }
  }
  return "La gateway est injoignable. Rien n'a changé.";
}
