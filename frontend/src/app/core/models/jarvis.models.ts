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
  /** F-255 / SF-255-01 — le tour vocal de l'énoncé. */
  turnId?: string | null;
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
  /** F-253 / SF-253-06 — la cause d'un tour vide (arrêt, outils, motifs de rejet). */
  emptyDiagnostic?: string | null;
  correction?: boolean;
  /** F-241 / SF-241-03 — le portrait servait à l'échange, et phrases écartées par ses interdits (jamais le texte). */
  portraitOn?: boolean | null;
  portraitBlocked?: number | null;
  /** F-241 / SF-241-04 — l'allusion personnelle du tour (jamais le texte). */
  personalAllusion?: 'SPONTANEE' | 'REPONSE' | 'AUCUNE' | null;
  /** F-247 / SF-247-01 — poste éteint, question d'état, issue de la réponse, lecture du bloc (jamais le texte). */
  hostOffline?: boolean | null;
  stateQuestion?: boolean | null;
  stateAnswer?: 'UNKNOWN' | 'KNOWN_STALE' | 'KNOWN_FRESH' | 'NA' | null;
  stateBlockMs?: number | null;
  /** F-248 / SF-248-01 — délai première phrase jouée → première pancarte (ms), nombre de pancartes (jamais le contenu). */
  cardsLagMs?: number | null;
  cardsCount?: number | null;
  /**
   * F-233 / SF-233-13 — pourquoi l'écoute n'a pas été faite en flux (liste fermée, `null` si le flux a servi), et la
   * page tournait dans le compagnon Android. Jamais de contenu.
   */
  streamFallback?: 'ticket_refuse' | 'ticket_indisponible' | 'ticket_echec' | 'micro' | 'ouverture_delai'
    | 'ouverture_refusee' | 'ouverture_reseau' | 'ouverture_fermee' | 'capture' | 'coupe' | 'autre' | null;
  companion?: boolean | null;
  /**
   * F-252 / SF-252-01 — la vitesse : préparation serveur du cerveau rapide (ms), issue de la réponse provisoire
   * (SF-252-02/04), silence réel à la fin de tour (ms) et anticipations abandonnées (parole reprise). Jamais de contenu.
   */
  prepMs?: number | null;
  speculation?: 'adoptee' | 'relancee' | 'perdue' | 'anticipee' | null;
  endSilenceMs?: number | null;
  anticipationsDropped?: number | null;
  /** F-255 / SF-255-01 — le tour vocal et la page de l'écran (identifiants aléatoires, jamais de contenu). */
  turnId?: string | null;
  pageId?: string | null;
  /** F-257 / SF-257-01 — la boucle vocale du tour (`legacy` : boucle de l'écran ; `stream` : flux permanent). */
  loop?: 'legacy' | 'stream';
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
  /** F-244 / SF-244-04 — le courrier (F-242) et l'agenda (F-243) ; absents d'une passerelle antérieure. */
  mail?: number;
  agenda?: number;
}

/** F-239 / SF-239-12 — aujourd'hui et le mois en cours ; F-244 / SF-244-04 — et le plafond (jauge). */
export interface JarvisCostSummary {
  today: JarvisCosts;
  month: JarvisCosts;
  budget?: JarvisBudgetStatus | null;
}

/** F-244 / SF-244-03 — l'état du budget de Jarvis : 80 % (ALERT), 100 % (SOFT), 2 × (HARD). */
export type JarvisBudgetState = 'NONE' | 'ALERT' | 'SOFT' | 'HARD';

/** F-244 / SF-244-03 — le plafond de Jarvis du compte, en euros. */
export interface JarvisBudgetStatus {
  monthlyCapEur: number;
  applyOnByok: boolean;
  monthSpentEur: number;
  percent: number;
  state: JarvisBudgetState;
  applies: boolean;
}

/** F-244 / SF-244-03 — les refus nommés du budget de Jarvis, émis dans le flux d'un tour avant tout appel. */
export const JARVIS_BUDGET_CONFIRM = 'jarvis_budget_confirm';
export const JARVIS_BUDGET_HARD = 'jarvis_budget_hard';

/** F-239 / SF-239-04 — un énoncé retranscrit par la passerelle (Scribe v2 batch + vocabulaire). */
export interface JarvisUtterance {
  text: string;
  /** Le fournisseur (ex. `elevenlabs-batch`), pour la mesure. */
  provider: string;
  /** La durée de l'appel au fournisseur, en ms. */
  ms: number;
}

/** F-233 / SF-233-04 — une session d'écoute en flux : de quoi parler directement au fournisseur (jamais la clé). */
/** F-257 / SF-257-03 — le ticket de session vocale (`POST /api/jarvis/voice-session`) ; jamais journalisé. */
export interface JarvisVoiceTicket {
  ticket: string;
  url: string;
  expiresAt: string;
  sampleRate: number;
}

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
