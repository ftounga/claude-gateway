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

// ------------------------------------------------------------------ les trois colonnes (SF-102-02)

export type RadarSubjectState =
  | 'NEW' | 'ADVANCING' | 'WAITING' | 'BLOCKED' | 'DORMANT' | 'CLOSE_PROPOSED' | 'CLOSED';

export type RadarCommitmentDirection = 'ME_TO_OTHER' | 'OTHER_TO_ME' | 'INTRODUCTION';

export type RadarEvidenceSource = 'TEAMS_MESSAGE' | 'TEAMS_MEETING' | 'LOCAL_RECORDING' | 'USER_NOTE' | 'PASTED_MAIL';

/** Une personne désignée par un engagement. */
export interface RadarPersonRef {
  id: string;
  displayName: string;
}

/** Un engagement (F-99). Une personne vide, c'est « moi ». */
export interface RadarCommitmentView {
  id: string;
  subjectId: string;
  subjectName: string;
  direction: RadarCommitmentDirection;
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
  followUpDueOn: string | null;
  followUpDue: boolean;
}

/** Un engagement dans sa colonne. */
export interface BoardCommitment {
  commitment: RadarCommitmentView;
  source: RadarEvidenceSource | null;
  sourceAt: string | null;
  deepLink: string | null;
  question: boolean;
  due: boolean;
  overdueDays: number;
}

/** Un sujet dans une liste (F-99). */
export interface RadarSubjectSummary {
  id: string;
  name: string;
  state: RadarSubjectState;
  nextStep: string | null;
  dueDate: string | null;
  lastActivityAt: string | null;
  openCommitments: number;
  awake: boolean;
}

/** Un sujet dans la colonne du milieu. */
export interface BoardSubject {
  subject: RadarSubjectSummary;
  line: string | null;
  sources: number;
  people: string[];
}

/** Les trois colonnes (F-102 / SF-102-02) — `GET /api/radar/hosts/{id}/board`. */
export interface RadarBoard {
  toDo: BoardCommitment[];
  subjects: BoardSubject[];
  waiting: BoardCommitment[];
}

/** Une ligne du journal des corrections (F-99) : ce qu'un geste a écrit, et qu'on peut annuler. */
export interface RadarCorrection {
  id: string;
  subjectId: string | null;
  action: string;
  createdAt: string;
  undoneAt: string | null;
}

/** Une clôture, et les engagements encore ouverts du sujet (F-99 / SF-99-04). */
export interface RadarClosure {
  correction: RadarCorrection;
  openCommitments: RadarCommitmentView[];
}

/** Les gestes sur un engagement. */
export type CommitmentGesture = 'DONE' | 'NOT_MINE' | 'POSTPONE' | 'CONFIRM' | 'ABANDON';

/** Une règle posée sur un fil : *ignorer ce fil* ou *lire ce canal*. */
export interface RadarThreadRule {
  id: string;
  conversationRef: string;
  rule: 'IGNORE' | 'READ_CHANNEL';
  label: string | null;
  createdAt: string;
}

// ------------------------------------------------------------------ Donner la nouvelle (F-104 / SF-104-02)

/** Ce qu'une nouvelle a écrit — dit par la gateway, jamais par le modèle. */
export interface RadarNewsChange {
  kind: string;
  subjectId: string | null;
  subjectName: string | null;
  correctionId: string | null;
  sentence: string;
}

/** L'en-tête d'un courriel collé, tel que la gateway l'a lu. */
export interface RadarPastedMail {
  sender: string;
  sentAt: string;
  subject: string | null;
  /** Faux si la date du courriel était illisible : la preuve est alors datée du collage. */
  datedFromMail: boolean;
}

/** La réponse du Radar à une nouvelle. */
export interface RadarNews {
  understanding: string;
  changes: RadarNewsChange[];
  /** La preuve rangée : c'est elle qu'on annule. `null` si rien n'a été écrit. */
  evidenceId: string | null;
  source: 'USER_NOTE' | 'PASTED_MAIL';
  mail: RadarPastedMail | null;
  stoppedEarly: boolean;
}

/** Une nouvelle annulée. */
export interface RadarNewsUndo {
  evidenceId: string;
  undone: number;
}

// ------------------------------------------------------------ Déposer un enregistrement (F-104 / SF-104-04)

/** Un dépôt ouvert sur le poste. */
export interface RadarDepositOpened {
  uploadId: string;
  chunkBytes: number;
  maxBytes: number;
}

/** Un morceau reçu par le poste. */
export interface RadarDepositChunk {
  received: number;
}

/** Un enregistrement déposé dans le dossier du poste. */
export interface RadarDepositDone {
  fileName: string;
  title: string;
  recordedAt: string;
  sizeBytes: number;
}

// ------------------------------------------------------------ Relances et présentations (F-104 / SF-104-05)

/** Relance ou présentation. */
export type RadarDraftKind = 'FOLLOW_UP' | 'INTRODUCTION';

/** Un brouillon préparé : jamais envoyé. */
export interface RadarDraft {
  kind: RadarDraftKind;
  text: string;
  /** La conversation Teams d'origine, ou `null`. */
  conversationUrl: string | null;
  preparedAt: string;
}
