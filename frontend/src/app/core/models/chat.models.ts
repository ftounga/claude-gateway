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
