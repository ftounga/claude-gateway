import { RadarSubjectDetail } from '../../core/models/radar-subject.models';

/** Jeux de test de la page sujet (F-103) — importés par les specs seulement. */

/** Un sujet minimal ; chaque test n'écrit que ce qu'il regarde. */
export function subjectDetail(extra: Partial<RadarSubjectDetail> = {}): RadarSubjectDetail {
  return {
    id: 's1', name: 'MFA prestataires', state: 'ADVANCING', nextStep: null, dueDate: null,
    lastActivityAt: null, createdAt: null, mergedIntoId: null, previousState: null,
    closeProposedAt: null, closeSignalEvidenceIds: [], closedAt: null, dormantSince: null,
    wokeAt: null, wakeEvidenceIds: [], nameSovereign: false, stateSovereign: false,
    nextStepSovereign: false, dueDateSovereign: false, aliases: [], stateEvidenceIds: [],
    nextStepEvidenceIds: [], dueDateEvidenceIds: [], summary: [], people: [], commitments: [],
    chronology: [],
    ...extra,
  };
}
