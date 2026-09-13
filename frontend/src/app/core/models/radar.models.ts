/**
 * **Le Radar d'un client, vu par son onglet dans la Vigie** (F-102).
 *
 * <p>Miroir des vues de la gateway : `RadarBoardViews` (résumé du matin) et `RadarViews` (synchros,
 * engagements, sujets). Rien n'est calculé ici qui ne soit déjà dit par la gateway.</p>
 */

export type RadarSyncStatus = 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

/** Un manque d'une synchro, et les gestes qu'il permet (F-100 / SF-100-04). */
export interface RadarCoverageItem {
  ref: string;
  label: string;
  /** `CONVERSATION`, `CHANNEL`, `MEETING`, `RECORDING`. */
  kind: string;
  /** `PARTIAL`, `FAILED`, `UNREAD_CHANNEL`, `NO_TRANSCRIPT`, `DENIED`, `UNAVAILABLE`, `DATE_GUESSED`… */
  status: string;
  detail: string | null;
  /** `IGNORE`, `READ_CHANNEL`. */
  actions: string[];
  /** La règle déjà posée sur ce fil, ou `null`. */
  rule: string | null;
}

/** Ce que la couverture dit en tête (F-100 / SF-100-04). */
export interface RadarCoverageSummary {
  headline: string;
  remedy: string | null;
  items: RadarCoverageItem[];
}

/** Une synchro du Radar. */
export interface RadarSyncView {
  id: string;
  status: RadarSyncStatus;
  startedAt: string | null;
  finishedAt: string | null;
  trigger: 'SCHEDULED' | 'MANUAL' | 'CATCH_UP' | string | null;
  scheduledFor: string | null;
  summary: RadarCoverageSummary | null;
}

export type BriefKind =
  | 'WAKE' | 'OVERDUE' | 'FOLLOW_UP' | 'CLOSE_PROPOSED' | 'NEW_SUBJECTS' | 'QUESTION' | 'MOVED' | 'DORMANT' | 'CALM';

/** Une phrase du résumé du matin, et l'objet du registre qui la fonde. */
export interface BriefSentence {
  kind: BriefKind;
  text: string;
  subjectId: string | null;
  commitmentId: string | null;
}

/** Les compteurs du Radar d'un client. */
export interface BriefCounts {
  toDoByMe: number;
  followUpsDue: number;
  introductions: number;
  subjectsFollowed: number;
  blockedSubjects: number;
  /** Ce qui réclame un geste. */
  toHandle: number;
}

/** Une source lue par la dernière synchro. */
export interface CoverageLine {
  source: 'TEAMS' | 'MEETINGS' | 'RECORDINGS' | string;
  ok: boolean;
  text: string;
}

/** Le résumé du matin (F-102 / SF-102-01) — `GET /api/radar/hosts/{id}/brief`. */
export interface RadarBrief {
  generatedAt: string;
  since: string;
  sentences: BriefSentence[];
  counts: BriefCounts;
  running: RadarSyncView | null;
  lastSync: RadarSyncView | null;
  coverageComplete: boolean;
  coverageWarning: string | null;
  coverageLines: CoverageLine[];
}

/** « Synchroniser maintenant » accepté. */
export interface RadarSyncStarted {
  syncId: string;
  trigger: string;
  startedAt: string;
}

/** Une règle posée sur un fil : *ignorer ce fil* ou *lire ce canal*. */
export interface RadarThreadRule {
  id: string;
  conversationRef: string;
  rule: 'IGNORE' | 'READ_CHANNEL';
  label: string | null;
  createdAt: string;
}
