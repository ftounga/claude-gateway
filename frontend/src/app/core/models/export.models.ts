/** Contrats DTO de l'export (F-14). Figés par le backend (SF-14-01). */

/** Formats d'export supportés. */
export type ExportFormat = 'markdown' | 'pdf';

/** Citation à faire figurer dans l'export d'une réponse (miroir de F-07). */
export interface AnswerExportCitation {
  documentId: string | null;
  filename: string;
  page: number | null;
  chunkIndex: number;
  snippet: string;
}

/** Corps de POST /api/export/answer (export stateless d'une réponse citée). */
export interface AnswerExportRequest {
  question: string;
  answer: string;
  model?: string | null;
  grounded?: boolean;
  citations?: AnswerExportCitation[];
}
