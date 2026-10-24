/**
 * Un encart mail/message marqué envoyé (F-211 / SF-211-01). Réponse de
 * `GET|POST /api/workspaces/{id}/messages/...sends`.
 */
export interface MessageSend {
  messageId: string;
  /** Rang du bloc mail/message dans la réponse complète. */
  blockIndex: number;
  kind: 'MAIL' | 'MESSAGE';
  subject: string | null;
  recipient: string | null;
  /** Instant de l'envoi marqué (ISO, horloge du serveur). */
  sentAt: string;
  /** Clé de l'attente passée « Demandé » par ce geste ; absente sinon. */
  attenteKey: string | null;
}
