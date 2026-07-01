/** Contrat DTO de l'API des modèles de prompts (F-13). Figé par le backend (SF-13-01). */

/** Catégorie métier d'un modèle. Miroir de l'énumération backend `TemplateCategory`. */
export type TemplateCategory = 'AUDIT' | 'REPORT' | 'OTHER';

/**
 * Réponse de `GET/POST/PUT /api/templates` (et éléments de la liste). Le contenu est inclus dans
 * la liste (modèles bornés à 10000 caractères) pour permettre la copie sans requête additionnelle.
 */
export interface TemplateResponse {
  id: string;
  name: string;
  category: TemplateCategory;
  content: string;
  createdAt: string;
  updatedAt: string;
}

/** Corps de `POST` / `PUT /api/templates`. `category` optionnelle (défaut `OTHER` côté backend). */
export interface TemplateRequest {
  name: string;
  category: TemplateCategory;
  content: string;
}
