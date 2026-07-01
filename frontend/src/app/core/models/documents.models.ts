/** Contrat DTO de l'API OCR documents (F-05). Figé par le backend (SF-05-01/02). */

/** Cycle de vie OCR d'un document. `INDEXED` sera ajouté par F-06 (post-OCR). */
export type DocumentStatus = 'UPLOADED' | 'PROCESSING' | 'EXTRACTED' | 'FAILED';

/** Réponse de POST /api/documents et éléments de GET /api/documents. */
export interface DocumentResponse {
  id: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
  status: DocumentStatus;
  createdAt: string;
}

/** Réponse de GET /api/documents/{id} : détail incluant le texte extrait. */
export interface DocumentDetailResponse extends DocumentResponse {
  extractedText: string | null;
  errorMessage: string | null;
}
