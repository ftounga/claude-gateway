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
  startedAt: string;
  endedAt: string | null;
  createdAt: string;
}

/** Demande « Rejoindre & capturer ». */
export interface CreateMeetingRequest {
  meetingUrl: string;
  title?: string | null;
  subjectId?: string | null;
  consentAcknowledged: boolean;
  retentionDays?: number | null;
}
