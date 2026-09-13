/**
 * **La page d'un sujet du Radar** telle que la gateway la rend (F-99 `RadarViews`, lue par F-103).
 *
 * <p>Chaque valeur affirmée porte les <b>identifiants de ses preuves</b> : l'écran rend chaque phrase
 * avec ses renvois (cadrage §4.1), il n'a jamais à les deviner.</p>
 */

/** État d'un sujet (cadrage §3 et §6). */
export type RadarSubjectState =
  | 'NEW'
  | 'ADVANCING'
  | 'WAITING'
  | 'BLOCKED'
  | 'DORMANT'
  | 'CLOSE_PROPOSED'
  | 'CLOSED';

/** D'où vient une preuve (cadrage §3). */
export type RadarEvidenceSource =
  | 'TEAMS_MESSAGE'
  | 'TEAMS_MEETING'
  | 'LOCAL_RECORDING'
  | 'USER_NOTE'
  | 'PASTED_MAIL';

/** Rôle d'une personne sur un sujet. */
export type RadarRole = 'DECIDES' | 'DRIVES' | 'EXPERT' | 'INFORMED';

/** Une preuve : un extrait, pas une archive. */
export interface RadarEvidenceView {
  id: string;
  source: RadarEvidenceSource;
  sourceRef: string;
  occurredAt: string;
  quote: string;
  deepLink: string | null;
  authorPersonId: string | null;
}

/** Une phrase du résumé et ses renvois. */
export interface RadarSentenceView {
  id: string;
  position: number;
  text: string;
  evidenceIds: string[];
}

/** Une personne et son rôle sur le sujet. */
export interface RadarRoleView {
  id: string;
  personId: string;
  displayName: string;
  jobTitle: string | null;
  role: RadarRole;
  evidenceIds: string[];
}

/** Une personne désignée par un engagement. */
export interface RadarPersonRef {
  id: string;
  displayName: string;
}

/** Un engagement du sujet. Une personne vide, c'est « moi ». */
export interface RadarCommitmentView {
  id: string;
  subjectId: string;
  subjectName: string;
  direction: 'ME_TO_OTHER' | 'OTHER_TO_ME' | 'INTRODUCTION';
  description: string;
  fromPerson: RadarPersonRef | null;
  toPerson: RadarPersonRef | null;
  otherPerson: RadarPersonRef | null;
  dueDate: string | null;
  dueDeduced: boolean;
  status: 'OPEN' | 'KEPT' | 'POSTPONED' | 'ABANDONED';
  certainty: 'CERTAIN' | 'PROBABLE';
  sovereign: boolean;
  disowned: boolean;
  evidenceIds: string[];
  followUpDue: boolean;
}

/** Un autre nom du sujet. */
export interface RadarAliasView {
  id: string;
  alias: string;
  origin: string;
  rejected: boolean;
}

/** La page d'un sujet — `GET /api/radar/hosts/{hostId}/subjects/{subjectId}`. */
export interface RadarSubjectDetail {
  id: string;
  name: string;
  state: RadarSubjectState;
  nextStep: string | null;
  dueDate: string | null;
  lastActivityAt: string | null;
  createdAt: string | null;
  mergedIntoId: string | null;
  previousState: RadarSubjectState | null;
  closeProposedAt: string | null;
  closeSignalEvidenceIds: string[];
  closedAt: string | null;
  dormantSince: string | null;
  wokeAt: string | null;
  wakeEvidenceIds: string[];
  nameSovereign: boolean;
  stateSovereign: boolean;
  nextStepSovereign: boolean;
  dueDateSovereign: boolean;
  aliases: RadarAliasView[];
  stateEvidenceIds: string[];
  nextStepEvidenceIds: string[];
  dueDateEvidenceIds: string[];
  summary: RadarSentenceView[];
  people: RadarRoleView[];
  commitments: RadarCommitmentView[];
  chronology: RadarEvidenceView[];
}
