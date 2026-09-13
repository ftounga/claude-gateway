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

/** Ce que le Radar ne sait pas sur un sujet (SF-103-02). */
export type RadarUnknownKind =
  | 'COVERAGE'
  | 'NEXT_STEP'
  | 'DUE_DATE'
  | 'DECIDER'
  | 'SILENCE'
  | 'OWNER'
  | 'DEDUCED_DUE';

/** La personne à qui poser la question, et pourquoi elle. */
export interface RadarAskView {
  personId: string;
  displayName: string;
  jobTitle: string | null;
  role: RadarRole | null;
  reason: string;
}

/** La réponse préparée pour le manager (SF-103-03) — rien n'est persisté côté gateway. */
export interface RadarManagerAnswer {
  text: string;
  preparedAt: string;
  coverageIncomplete: boolean;
  unknownsCount: number;
}

/** Un manque — `GET /api/radar/hosts/{hostId}/subjects/{subjectId}/unknowns`. */
export interface RadarUnknownView {
  kind: RadarUnknownKind;
  question: string;
  ask: RadarAskView | null;
  evidenceIds: string[];
  commitmentId: string | null;
}

/**
 * Une ligne du journal des corrections d'un sujet (F-99) — `GET /api/radar/hosts/{hostId}/corrections?subjectId=`.
 * Les valeurs avant / après ne portent que les champs touchés par le geste.
 */
export interface RadarCorrectionView {
  id: string;
  subjectId: string | null;
  targetKind: 'SUBJECT' | 'COMMITMENT';
  targetId: string;
  action: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  createdAt: string;
  undoneAt: string | null;
}

/** Séparer un sujet (F-99 / SF-99-06) : le nouveau nom, les preuves et engagements qui partent. */
export interface RadarSplitRequest {
  name: string;
  evidenceIds: string[];
  commitmentIds: string[];
}

/** Une nouvelle annulée depuis la chronologie (F-104 / SF-104-02). */
export interface RadarNewsUndoResult {
  evidenceId: string;
  undone: number;
}

/** D'où vient un lien sujet ↔ projet (F-106 / SF-106-06). */
export type RadarSubjectProjectOrigin = 'USER' | 'PROPOSED';

/** Ce qu'il en est d'un lien : confirmé, ou une question posée par l'analyse. */
export type RadarSubjectProjectState = 'CONFIRMED' | 'PROPOSED';

/** Un projet de la Forge lié au sujet, ou proposé. */
export interface RadarSubjectProjectLink {
  workspaceId: string;
  name: string;
  projectPath: string | null;
  origin: RadarSubjectProjectOrigin;
  state: RadarSubjectProjectState;
}

/** Un projet du poste qu'on peut lier. */
export interface RadarProjectCandidate {
  workspaceId: string;
  name: string;
  projectPath: string | null;
}

/** `GET /api/radar/hosts/{hostId}/subjects/{subjectId}/projects` (F-106 / SF-106-06). */
export interface RadarSubjectProjects {
  /** Le client est activé dans la Forge : « Voir le projet dans la Forge » a une destination. */
  inForge: boolean;
  links: RadarSubjectProjectLink[];
  candidates: RadarProjectCandidate[];
}

/** Un sujet nommé, pour la passerelle de la Forge. */
export interface RadarSubjectRef {
  id: string;
  name: string;
}

/** `GET /api/radar/hosts/{hostId}/project-subjects` : les sujets liés, projet par projet. */
export interface RadarProjectSubjects {
  workspaceId: string;
  subjects: RadarSubjectRef[];
}
