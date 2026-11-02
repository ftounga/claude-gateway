/**
 * Le mode Jarvis (F-231) — contrats relus de la passerelle.
 *
 * SF-231-06 : `GET /api/workspaces/{id}/jarvis/absence?since=` et `GET /api/jarvis/to-send?hostId=`.
 */

/** F-235 / SF-235-09 (D18) — le réglage d'humour de Jarvis : aucun ou léger (défaut). */
export type JarvisHumour = 'NONE' | 'LIGHT';

/** F-235 / SF-235-09 (D18) — les préférences de Jarvis du compte. */
export interface JarvisPreferences {
  humour: JarvisHumour;
  /** F-239 / SF-239-03 — garder mes énoncés pour le banc d'écoute (ADMIN, 7 jours) ; absent = non. */
  captureAudio?: boolean;
}

/** F-239 / SF-239-03 — ce qui accompagne un énoncé gardé pour le banc (jamais pour un autre compte). */
export interface JarvisCaptureMeta {
  seconds: number | null;
  listenMode: string;
  provider: string | null;
  streamText: string;
  finalText: string;
}

/** Une demande dite à la voix, et la phrase de sa réponse. */
export interface JarvisAbsenceTurn {
  messageId: string;
  request: string;
  at: string;
  chantierNumber: number | null;
  replyId: string | null;
  reply: string | null;
  /** F-235 / SF-235-06 — `FAST` : réponse rapide de Jarvis ; `DELEGATED` : tour Opus délégué ; absent sinon. */
  brain?: 'FAST' | 'DELEGATED' | null;
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
  /** F-241 / SF-241-03 (D18) — l'encart reprend un élément du portrait : « élément personnel — à relire ». */
  personal?: boolean;
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

/**
 * F-232 / SF-232-02 — le compagnon Android servi par la gateway (`GET /api/runner/download/android/version`).
 * Tous les champs sont `null` quand `available` est faux (absent, mal configuré ou mal signé).
 */
export interface JarvisCompanionVersion {
  available: boolean;
  versionCode: number | null;
  versionName: string | null;
  sha256: string | null;
  signingCertSha256: string | null;
  sizeBytes: number | null;
}

/** F-232 / SF-232-03 — le code d'appairage du compagnon Android : 8 caractères, à usage unique, 5 minutes. */
export interface JarvisCompanionPairingCode {
  code: string;
  expiresAt: string;
}

/** F-232 / SF-232-03 — un compagnon Android appairé (annonces téléphone verrouillé). */
export interface JarvisCompanionDevice {
  id: string;
  label: string;
  pairedAt: string;
  lastSeenAt: string | null;
}

/** F-233 / SF-233-01 — Ce que l'écran déclare (`POST /api/jarvis/timings`). */
export interface TurnTimingReport {
  workspaceId: string | null;
  /** SF-233-11 — `CONV_*` : le tour a été pris en conversation mains libres. */
  listenMode: 'BATCH' | 'STREAM' | 'CONV_BATCH' | 'CONV_STREAM';
  sttProvider: string | null;
  ttsProvider: string | null;
  toolTurn: boolean;
  speechMs: number | null;
  transcriptMs: number | null;
  reactionMs: number | null;
  sentMs: number | null;
  firstTokenMs: number | null;
  firstOralSentenceMs: number | null;
  firstAudioByteMs: number | null;
  firstSoundMs: number | null;
  doneMs: number | null;
  interruptionsDetected: number;
  interruptionsRestored: number;
  interruptionsConfirmed: number;
  acknowledgements: number;
  falseTriggers: number;
  /** F-235 / SF-235-07 (D14) — le cerveau du tour et ce que ses garde-fous ont fait (facultatif, sans contenu). */
  brain?: 'FAST' | 'FAST_DELEGATED' | 'OPUS';
  fastModel?: string | null;
  fastFirstTokenMs?: number | null;
  groundingTrips?: number;
  fillersRemoved?: number;
  repeatsRemoved?: number;
  imitationsRemoved?: number;
  forcedDelegation?: boolean;
  delegationReason?: string | null;
  correction?: boolean;
  /** F-241 / SF-241-03 — le portrait servait à l'échange, et phrases écartées par ses interdits (jamais le texte). */
  portraitOn?: boolean | null;
  portraitBlocked?: number | null;
  /** F-241 / SF-241-04 — l'allusion personnelle du tour (jamais le texte). */
  personalAllusion?: 'SPONTANEE' | 'REPONSE' | 'AUCUNE' | null;
  /**
   * F-239 / SF-239-01 (D3b) — une parole refusée par la porte de parole réelle (aucun tour) : la raison et la longueur
   * du texte transcrit, **jamais le texte**.
   */
  rejectedKind?: 'empty' | 'noise' | 'hallucination' | 'no_voice' | 'implausible' | null;
  rejectedChars?: number | null;
  /**
   * F-240 / SF-240-01 — ce qui a été dit (jamais le texte) : mots, caractères et durée audio de la réponse dite (hors
   * annonce), mots de l'annonce avant outils, coupe de l'écran (D5), invitation ciblée dite (D6), étage atteint
   * (1 dit, 2 essentiel, 3 détail) et son chemin.
   */
  spokenWords?: number | null;
  spokenChars?: number | null;
  spokenMs?: number | null;
  announceWords?: number | null;
  oralCut?: boolean | null;
  invitation?: boolean | null;
  depthReached?: 1 | 2 | 3 | null;
  depthVia?: 'voix' | 'toucher' | null;
}

/** F-239 / SF-239-12 — ce que Jarvis a coûté, en euros, par poste. */
export interface JarvisCosts {
  fastBrain: number;
  opus: number;
  voice: number;
  listening: number;
  total: number;
  opusTurns: number;
}

/** F-239 / SF-239-12 — aujourd'hui et le mois en cours. */
export interface JarvisCostSummary {
  today: JarvisCosts;
  month: JarvisCosts;
}

/** F-239 / SF-239-04 — un énoncé retranscrit par la passerelle (Scribe v2 batch + vocabulaire). */
export interface JarvisUtterance {
  text: string;
  /** Le fournisseur (ex. `elevenlabs-batch`), pour la mesure. */
  provider: string;
  /** La durée de l'appel au fournisseur, en ms. */
  ms: number;
}

/** F-233 / SF-233-04 — une session d'écoute en flux : de quoi parler directement au fournisseur (jamais la clé). */
export interface JarvisSttTicket {
  sessionId: string;
  provider: string;
  /** L'URL WebSocket, sans le jeton. */
  url: string;
  /** Le jeton éphémère (usage unique). */
  token: string;
  expiresAt: string;
  sampleRate: number;
  maxSeconds: number;
}
