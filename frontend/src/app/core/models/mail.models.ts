/** **L'état de remise d'un courriel du client** (F-110 / SF-110-02), jamais le corps. */
export interface ClientEmailView {
  id: string;
  recipient: string;
  recipientVerified: boolean;
  clientName: string;
  subject: string;
  attachmentCount: number;
  sizeBytes: number;
  status: 'PENDING' | 'SENDING' | 'SENT' | 'FAILED' | string;
  failureReason: string | null;
  createdAt: string;
  sentAt: string | null;
}

/**
 * **L'adresse de réception d'un client** (F-110 / SF-110-01), telle que la gateway la rend. Elle ne porte
 * jamais le code de vérification.
 */
export interface HostMailAddress {
  /** Adresse déclarée (vérifiée ou non), ou `null`. */
  address: string | null;
  verified: boolean;
  verifiedAt: string | null;
  /** Un code valable attend d'être saisi. */
  codePending: boolean;
  codeExpiresAt: string | null;
  /** Adresse du compte : le repli. */
  accountEmail: string | null;
  /** Destinataire effectif des courriels du client. */
  recipient: string | null;
  /** Vrai quand le destinataire est l'adresse du compte. */
  fallback: boolean;
  clientName: string;
}
