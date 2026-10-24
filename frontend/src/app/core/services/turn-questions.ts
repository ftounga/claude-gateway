import {
  AtelierNextStep,
  AtelierNextStepsChantier,
  AtelierQuestionAnswer,
  AtelierTurnQuestion,
  AtelierTurnQuestionState,
  AtelierTurnQuestions,
} from '../models/atelier.models';

const STATES: readonly AtelierTurnQuestionState[] = ['EN_COURS', 'EN_ATTENTE', 'TERMINEE', 'NON_ABOUTIE'];

function asState(value: unknown): AtelierTurnQuestionState {
  return STATES.includes(value as AtelierTurnQuestionState) ? (value as AtelierTurnQuestionState) : 'EN_ATTENTE';
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function normalizeQuestion(raw: unknown): AtelierTurnQuestion | null {
  const q = asRecord(raw);
  if (!q || typeof q['id'] !== 'number' || typeof q['libelle'] !== 'string') {
    return null;
  }
  return {
    id: q['id'],
    libelle: q['libelle'],
    dependsOn: typeof q['dependsOn'] === 'number' ? q['dependsOn'] : null,
    state: asState(q['state']),
    toolUseIds: Array.isArray(q['toolUseIds'])
      ? q['toolUseIds'].filter((id): id is string => typeof id === 'string')
      : [],
  };
}

function normalizeAnswer(raw: unknown): AtelierQuestionAnswer | null {
  const a = asRecord(raw);
  if (!a || typeof a['id'] !== 'number') {
    return null;
  }
  return {
    id: a['id'],
    libelle: typeof a['libelle'] === 'string' ? a['libelle'] : '',
    status: asState(a['status']),
    essential: typeof a['essential'] === 'string' && a['essential'].trim() ? a['essential'] : null,
    body: typeof a['body'] === 'string' ? a['body'] : '',
    nextSteps: Array.isArray(a['nextSteps']) ? (a['nextSteps'] as AtelierNextStep[]) : [],
    chantier: asRecord(a['chantier']) ? (a['chantier'] as unknown as AtelierNextStepsChantier) : null,
  };
}

/**
 * Lit les questions d'un tour (F-210 / SF-210-01/02) : instantané SSE `questions`, ou
 * `terminal_json.questions` (+ `answers` passées à part). `null` si rien d'exploitable — mieux vaut aucune
 * piste qu'une piste creuse.
 */
export function normalizeTurnQuestions(raw: unknown, answers?: unknown): AtelierTurnQuestions | null {
  const snapshot = asRecord(raw);
  if (!snapshot || !Array.isArray(snapshot['questions'])) {
    return null;
  }
  const questions = snapshot['questions']
    .map(normalizeQuestion)
    .filter((q): q is AtelierTurnQuestion => q !== null);
  if (questions.length === 0) {
    return null;
  }
  const fusion = asRecord(snapshot['fusion']);
  const rawAnswers = answers ?? snapshot['answers'];
  const parsedAnswers = Array.isArray(rawAnswers)
    ? rawAnswers.map(normalizeAnswer).filter((a): a is AtelierQuestionAnswer => a !== null)
    : [];
  return {
    questions,
    fusion: fusion ? { raison: typeof fusion['raison'] === 'string' ? fusion['raison'] : '' } : null,
    ...(parsedAnswers.length > 0 ? { answers: parsedAnswers } : {}),
  };
}
