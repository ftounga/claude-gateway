import { HttpErrorResponse } from '@angular/common/http';

/**
 * Utilitaires d'extraction d'un message d'erreur **lisible** à partir d'une réponse HTTP en échec.
 *
 * <p>Le backend renvoie un corps JSON structuré `{ error: <code>, message: <texte> }` (voir
 * `GlobalExceptionHandler`). Au-delà de la limite de taille de l'ingress, nginx renvoie un `413`
 * avec une page HTML brute (non-JSON) : on ne l'affiche jamais telle quelle, on la traduit en
 * message métier. Fonctions pures, sans dépendance Angular hors typage — testables unitairement.</p>
 */

/** Taille max d'archive tolérée côté client, alignée sur l'ingress (`proxy-body-size: 155m`). */
export const MAX_UPLOAD_BYTES = 150 * 1024 * 1024;

/** Rappel des dossiers à exclure d'une archive de projet pour rester sous la limite. */
export const EXCLUDE_HINT = 'Excluez node_modules/, .git/, target/, dist/, build/ puis re-zippez.';

/** Message générique de dernier recours. */
export const GENERIC_ERROR = 'Une erreur est survenue. Veuillez réessayer.';

/** Message « archive trop volumineuse » sans taille connue (ex. 413 ingress). */
export const TOO_LARGE_MESSAGE = `Archive trop volumineuse (max 150 Mo). ${EXCLUDE_HINT}`;

/** Message « archive trop volumineuse » avec la taille effective de l'archive (contrôle client). */
export function oversizeMessage(bytes: number): string {
  const mb = Math.max(1, Math.round(bytes / (1024 * 1024)));
  return `Archive trop volumineuse (${mb} Mo, max 150 Mo). ${EXCLUDE_HINT}`;
}

/**
 * Renvoie un message d'erreur affichable pour l'utilisateur.
 *
 * @param error    l'erreur remontée par `HttpClient` (idéalement un {@link HttpErrorResponse})
 * @param fallback message de repli si aucune cause exploitable n'est trouvée
 */
export function httpErrorMessage(error: unknown, fallback: string = GENERIC_ERROR): string {
  if (error instanceof HttpErrorResponse) {
    // 413 : l'ingress a coupé la requête → corps HTML brut inexploitable, on traduit.
    if (error.status === 413) {
      return TOO_LARGE_MESSAGE;
    }
    const message = extractMessage(error.error);
    if (message) {
      return message;
    }
  }
  return fallback;
}

/** Extrait le champ `message` d'un corps d'erreur, qu'il soit déjà désérialisé ou encore en texte. */
function extractMessage(body: unknown): string | null {
  if (body && typeof body === 'object') {
    const message = (body as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim().length > 0) {
      return message;
    }
    return null;
  }
  if (typeof body === 'string') {
    const trimmed = body.trim();
    // N'essaie de parser que ce qui ressemble à du JSON (jamais une page HTML).
    if (trimmed.startsWith('{')) {
      try {
        return extractMessage(JSON.parse(trimmed));
      } catch {
        return null;
      }
    }
  }
  return null;
}
