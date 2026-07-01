/** Contrat DTO de l'API d'upload (F-04). Figé par le backend (SF-04-01). */

/** Réponse de POST /api/upload : métadonnées du fichier téléversé. */
export interface UploadedFileResponse {
  id: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
}
