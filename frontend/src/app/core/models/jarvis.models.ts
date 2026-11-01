/**
 * Le mode Jarvis (F-231) — contrats relus de la passerelle.
 *
 * SF-231-06 : `GET /api/workspaces/{id}/jarvis/absence?since=` et `GET /api/jarvis/to-send?hostId=`.
 */

/** Une demande dite à la voix, et la phrase de sa réponse. */
export interface JarvisAbsenceTurn {
  messageId: string;
  request: string;
  at: string;
  chantierNumber: number | null;
  replyId: string | null;
  reply: string | null;
}

/** Une attente créée depuis la première demande dite à la voix. */
export interface JarvisAbsenceAttente {
  id: string;
  description: string;
  status: string;
  createdAt: string;
}

/** Un encart mail ou message préparé par l'agent. `sentAt` nul : pas marqué envoyé — à envoyer. */
export interface JarvisToSend {
  workspaceId: string;
  workspaceName: string | null;
  messageId: string;
  blockIndex: number;
  kind: 'MAIL' | 'MESSAGE';
  subject: string | null;
  recipient: string | null;
  proposedAt: string;
  sentAt: string | null;
  excerpt: string | null;
  chantierNumber: number | null;
  viaJarvis: boolean;
}

/** L'encart « Pendant ton absence, via Jarvis ». `turns` vide : Jarvis n'a rien fait ici. */
export interface JarvisAbsence {
  since: string;
  turns: JarvisAbsenceTurn[];
  attentes: JarvisAbsenceAttente[];
  toSend: JarvisToSend[];
}

/** Le compteur « À envoyer (n) » du tableau du client. */
export interface JarvisToSendList {
  count: number;
  items: JarvisToSend[];
}

/** Le libellé court d'un encart : « Mail à Zahi — Compte forge ». */
export function toSendLabel(item: JarvisToSend): string {
  const what = item.kind === 'MAIL' ? 'Mail' : 'Message';
  const to = item.recipient ? ` à ${item.recipient}` : '';
  const subject = item.subject ? ` — ${item.subject}` : '';
  return `${what}${to}${subject}`;
}
