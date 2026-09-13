/**
 * **Un brouillon venu du Radar** (F-103 / SF-103-03) : la clé de l'état de navigation qui porte le texte
 * à déposer dans la zone de saisie d'une conversation, **sans l'envoyer**.
 *
 * <p>L'état de navigation, et jamais l'adresse : le brouillon cite un sujet d'un client, il n'a rien à
 * faire dans l'historique du navigateur ni dans un journal d'accès.</p>
 */
export const RADAR_DRAFT_STATE = 'radarDraft';

/** Longueur maximale d'un brouillon repris : au-delà, il n'est pas repris. */
export const RADAR_DRAFT_MAX_CHARS = 4000;

/** Le brouillon porté par un état de navigation, ou `null`. */
export function radarDraftFrom(state: unknown): string | null {
  if (!state || typeof state !== 'object') {
    return null;
  }
  const value = (state as Record<string, unknown>)[RADAR_DRAFT_STATE];
  return typeof value === 'string' && value.trim().length > 0 && value.length <= RADAR_DRAFT_MAX_CHARS
    ? value : null;
}
