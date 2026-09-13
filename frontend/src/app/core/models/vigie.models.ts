import { ClientSpace, HostMissionStatus } from './atelier.models';

/**
 * Un client et les espaces où il est activé (F-106 / SF-106-01) — `GET /api/runner-hosts/spaces`.
 * C'est la liste d'où la Vigie propose « activer un client de la Forge ».
 */
export interface HostSpaces {
  hostId: string;
  name: string;
  missionStatus: HostMissionStatus;
  spaces: ClientSpace[];
}

/** Pourquoi l'écran purge le Radar d'un client (F-99 / SF-99-05) ; `HOST_DELETED` est réservée à la gateway. */
export type RadarPurgeReason = 'VIGIE_REMOVED' | 'MISSION_CLOSED';

/** Ce que le bandeau de la Vigie lit d'un sujet du Radar (F-99) : son état, rien de plus. */
export interface VigieSubjectSummary {
  id: string;
  name: string;
  state: string;
}

/** Ce que le bandeau lit d'un engagement (F-99) : la relance due. */
export interface VigieCommitmentSummary {
  id: string;
  followUpDue: boolean;
}

/** Une synchro du Radar (F-100), vue par le bandeau. */
export interface VigieSyncSummary {
  id: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
  startedAt: string | null;
  finishedAt: string | null;
}

/** Un sujet d'une personne de l'annuaire (F-99). */
export interface VigiePersonSubject {
  subjectId: string;
  subjectName: string;
  state: string;
  role: string;
}

/** Une personne de l'annuaire du client (F-99) — l'onglet Personnes. */
export interface VigiePerson {
  id: string;
  displayName: string;
  jobTitle: string | null;
  lastInteractionAt: string | null;
  subjects: VigiePersonSubject[];
}

/** Ce que le Radar d'un client apporte au bandeau et à sa ligne de colonne. */
export interface VigieRadarCounts {
  followUpsDue: number;
  blockedSubjects: number;
  /** Ce qui réclame un geste dans le Radar du client (F-102 / SF-102-03). */
  toHandle?: number;
  lastSync: VigieSyncSummary | null;
}
