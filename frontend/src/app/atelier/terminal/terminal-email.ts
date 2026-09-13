import { AtelierTerminalBlock, AtelierTerminalEmail } from '../../core/models/atelier.models';

/**
 * **Le bloc « Courriel envoyé »** (F-110 / SF-110-02), en fonctions pures : ce que dit l'état de remise.
 */

/** Vrai quand l'envoi est terminé : plus rien à relire. */
export function isFinalEmailStatus(status: string | null | undefined): boolean {
  return status === 'SENT' || status === 'FAILED';
}

/** L'état de remise en toutes lettres. */
export function emailStateLabel(status: string | null | undefined, failureReason?: string | null): string {
  switch (status) {
    case 'SENT':
      return 'accepté par le relais';
    case 'FAILED':
      return failureReason ? `non remis : ${failureReason}` : 'non remis';
    default:
      return "en cours d'envoi";
  }
}

/** La ligne du bloc : « Courriel envoyé à … — objet — 2 pièces jointes ». */
export function emailHeadline(email: AtelierTerminalEmail): string {
  const attachments = email.attachmentCount > 0
    ? ` — ${email.attachmentCount} pièce${email.attachmentCount > 1 ? 's' : ''} jointe${email.attachmentCount > 1 ? 's' : ''}`
    : '';
  return `Courriel envoyé à ${email.recipient} — ${email.subject}${attachments}`;
}

/** Le repli, dit : `null` quand le destinataire est l'adresse vérifiée du client. */
export function emailFallbackNotice(email: AtelierTerminalEmail): string | null {
  return email.recipientVerified
    ? null
    : `Aucune adresse vérifiée pour ${email.clientName} : envoyé à l'adresse du compte.`;
}

/**
 * **Le bloc de transcription d'un courriel reçu au fil de l'eau.** Son `toolUseId` est celui de l'appel ; il est
 * rangé comme une carte (`withCards`) pour survivre au recalcul des blocs vivants.
 */
export function emailBlock(toolUseId: string, email: AtelierTerminalEmail): AtelierTerminalBlock {
  return {
    tool: 'email_me',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    email,
  };
}
