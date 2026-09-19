/**
 * Modèles de l'artefact « réunion » de la Vigie (F-128 / SF-128-01).
 */

/** États d'une capture de réunion. `RECORDING`/`PAUSED` gardent l'indicateur « capture en cours ». */
export type MeetingState = 'RECORDING' | 'PAUSED' | 'STOPPED' | 'FAILED';

/** Une réunion Teams rejointe et capturée depuis un poste. */
export interface TeamsMeeting {
  id: string;
  hostId: string;
  subjectId: string | null;
  title: string | null;
  meetingUrl: string;
  state: MeetingState;
  consentAcknowledged: boolean;
  retentionDays: number;
  captureRef: string | null;
  /** Vrai dès que l'audio capturé est remonté (SF-128-02) et lisible (SF-128-10). */
  hasAudio: boolean;
  /** Taille de l'audio capturé en octets, ou `null` tant qu'aucun audio. */
  audioBytes: number | null;
  /** Nombre d'images clés (deck reconstitué, SF-128-03). */
  imageCount: number;
  /** État de la transcription (SF-128-04). */
  transcriptStatus: TranscriptStatus;
  /** Langue détectée du transcript, ou `null`. */
  transcriptLang: string | null;
  /** Vrai dès qu'un transcript est rattaché (SF-128-04). */
  hasTranscript: boolean;
  startedAt: string;
  endedAt: string | null;
  createdAt: string;
}

/** État de la transcription d'une réunion (SF-128-04). */
export type TranscriptStatus = 'NONE' | 'PENDING' | 'TRANSCRIBING' | 'TRANSCRIBED' | 'FAILED';

/** L'exploitation d'une réunion par l'agent (SF-128-05). */
export interface MeetingInsights {
  summary: string;
  keyPoints: string[];
  decisions: string[];
  actions: string[];
  hasTranscript: boolean;
  imagesUsed: number;
  missing: string | null;
}

/** La réponse de l'agent à une question libre sur une réunion (SF-128-05). */
export interface MeetingAnswer {
  answer: string;
}

/** Un fichier de carte concerné par un rangement (SF-128-11). */
export interface PromotedCardFile {
  path: string;
  factsWritten: number;
  /** `WRITTEN` (écrit) ou `SKIPPED` (ignoré : absent, illisible, poste injoignable). */
  status: 'WRITTEN' | 'SKIPPED';
}

/** Le bilan d'un rangement d'une réunion dans la carte du poste (SF-128-11). */
export interface MeetingCardPromotion {
  files: PromotedCardFile[];
  /** Nombre total de faits durables réellement écrits dans la carte. */
  factsWritten: number;
  /** Un mot lisible quand rien n'est écrit (aucune carte active, rien de durable), ou `null`. */
  note: string | null;
}

/** Demande « Rejoindre & capturer ». */
export interface CreateMeetingRequest {
  meetingUrl: string;
  title?: string | null;
  subjectId?: string | null;
  consentAcknowledged: boolean;
  retentionDays?: number | null;
}
