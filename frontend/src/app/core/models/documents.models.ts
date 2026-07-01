/** Contrat DTO de l'API documents (OCR F-05 + ingestion RAG F-06). Figé par le backend. */

/**
 * Cycle de vie d'un document : OCR (F-05) puis ingestion RAG (F-06).
 * `INDEXING`/`INDEXED` ajoutés par F-06 (SF-06-01).
 */
export type DocumentStatus =
  | 'UPLOADED'
  | 'PROCESSING'
  | 'EXTRACTED'
  | 'INDEXING'
  | 'INDEXED'
  | 'FAILED';

/** Réponse de POST /api/documents et éléments de GET /api/documents. */
export interface DocumentResponse {
  id: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
  status: DocumentStatus;
  /** Nombre de chunks indexés (F-06). 0 tant que le document n'est pas `INDEXED`. */
  chunkCount: number;
  createdAt: string;
}

/** Réponse de GET /api/documents/{id} : détail incluant le texte extrait. */
export interface DocumentDetailResponse extends DocumentResponse {
  extractedText: string | null;
  errorMessage: string | null;
}

/**
 * Réponse de GET /api/documents/{id}/status (F-08 / SF-08-01) : vue légère pour le suivi/polling,
 * sans le texte extrait ni le brut fournisseur.
 */
export interface DocumentStatusResponse {
  id: string;
  status: DocumentStatus;
  chunkCount: number;
  errorMessage: string | null;
}
