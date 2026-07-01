/** Contrat DTO du Q&A documentaire (F-07). Figé par le backend (SF-07-01). */

/** Corps de POST /api/ask. */
export interface AskRequest {
  /** Question de l'utilisateur (requise, non vide). */
  question: string;
  /** Modèle souhaité (optionnel ; défaut backend sinon). */
  model?: string | null;
  /** Nombre de chunks de contexte souhaités (optionnel ; borné [1,20] côté backend). */
  topK?: number | null;
}

/** Citation d'un chunk ayant servi de contexte à la réponse. */
export interface Citation {
  documentId: string;
  filename: string;
  /** Numéro de page (peut être null : non dérivé en F-06). */
  page: number | null;
  chunkIndex: number;
  snippet: string;
}

/** Réponse de POST /api/ask. */
export interface AskResponse {
  answer: string;
  model: string;
  /** true si au moins un chunk a servi de contexte ; false en repli (réponse non ancrée). */
  grounded: boolean;
  /** Citations des chunks utilisés (vide en repli). */
  citations: Citation[];
}
