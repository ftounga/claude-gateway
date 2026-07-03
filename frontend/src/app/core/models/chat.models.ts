/** Contrats DTO de l'API de chat (F-02). Figés par le backend (SF-02-01). */

export type MessageRole = 'USER' | 'ASSISTANT';

/** Message d'une conversation. */
export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  model: string | null;
  createdAt: string;
}

/** Corps de POST /api/chat. */
export interface ChatRequest {
  conversationId?: string | null;
  message: string;
  model?: string | null;
  /** Identifiants de fichiers téléversés à rattacher (F-04 ; contrat importé de SF-04-02). */
  attachmentIds?: string[];
  /**
   * Identifiants de documents de la bibliothèque personnelle (F-08) dont le texte OCR est injecté
   * comme contexte de l'échange (F-24 ; contrat figé par SF-24-01 ; max 10).
   */
  libraryDocumentIds?: string[];
}

/** Réponse de POST /api/chat. */
export interface ChatResponse {
  conversationId: string;
  message: ChatMessage;
  model: string;
}

/** Vue résumée d'une conversation (liste latérale). */
export interface ConversationSummary {
  id: string;
  title: string;
  model: string;
  createdAt: string;
  updatedAt: string;
}

/** Vue détaillée d'une conversation (métadonnées + fil). */
export interface ConversationDetail extends ConversationSummary {
  messages: ChatMessage[];
}

/** Corps de PATCH /api/conversations/{id}. */
export interface RenameConversationRequest {
  title: string;
}

/** Réponse de GET /api/chat/models. */
export interface ModelsResponse {
  defaultModel: string;
  models: string[];
}

/**
 * Fichier téléversé rattaché à une conversation (F-23 ; contrat figé par SF-23-01).
 * Réponse de `GET /api/conversations/{id}/files`.
 */
export interface ConversationFile {
  id: string;
  filename: string;
  mediaType: string;
  sizeBytes: number;
  createdAt: string;
}
