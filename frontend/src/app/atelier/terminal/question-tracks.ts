import {
  AtelierTerminalBlock,
  AtelierTurnQuestion,
  AtelierTurnQuestionState,
  AtelierTurnQuestions,
} from '../../core/models/atelier.models';

/** Actions montrées au plus sous une piste (les plus récentes). */
export const TRACK_RECENT_ACTIONS = 3;

/** La demande envoyée par « Fusionner » (F-210 / SF-210-03) : visible, jamais silencieuse. */
export const MERGE_REQUEST = 'Traite ces questions ensemble, en une seule réponse.';
/** La demande envoyée par « Séparer ». */
export const SPLIT_REQUEST = 'Sépare ces questions : une réponse par question.';

const STATE_LABELS: Record<AtelierTurnQuestionState, string> = {
  EN_COURS: 'En cours',
  EN_ATTENTE: 'En attente',
  TERMINEE: 'Terminée',
  NON_ABOUTIE: 'Non aboutie',
};

/** L'état d'une question, dit en français ; inconnu ⇒ « En attente ». */
export function questionStateLabel(state: string | null | undefined): string {
  return STATE_LABELS[state as AtelierTurnQuestionState] ?? STATE_LABELS.EN_ATTENTE;
}

/** Une piste prête à afficher. */
export interface QuestionTrack {
  question: AtelierTurnQuestion;
  stateLabel: string;
  /** « en attente de Q1 » quand la question attend celle dont elle dépend ; sinon `null`. */
  waitingOn: string | null;
  /** Nombre d'actions rattachées. */
  actionCount: number;
  /** Les dernières actions (libellés), les plus récentes en dernier. */
  recent: string[];
}

/**
 * Les blocs rattachés à une question : par le champ `question` pendant le tour (les blocs vivants n'ont
 * pas d'identifiant d'appel), par l'identifiant d'appel dans l'historique.
 */
export function blocksOfQuestion(question: AtelierTurnQuestion, blocks: AtelierTerminalBlock[]): AtelierTerminalBlock[] {
  const ids = new Set(question.toolUseIds);
  return blocks.filter((block) =>
    block.question !== undefined
      ? block.question === question.id
      : block.toolUseId !== null && ids.has(block.toolUseId),
  );
}

/** Les pistes du tour, dans l'ordre des questions. */
export function questionTracks(questions: AtelierTurnQuestions | null | undefined,
  blocks: AtelierTerminalBlock[] = []): QuestionTrack[] {
  if (!questions) {
    return [];
  }
  return questions.questions.map((question) => {
    const own = blocksOfQuestion(question, blocks);
    const labels = own.map((block) => block.command || block.tool).filter((label) => !!label);
    // Le nombre d'actions : celles vues à l'écran, ou à défaut celles que la gateway a rattachées.
    const actionCount = Math.max(own.length, question.toolUseIds.length);
    return {
      question,
      stateLabel: questionStateLabel(question.state),
      waitingOn: question.state === 'EN_ATTENTE' && question.dependsOn !== null
        ? `en attente de Q${question.dependsOn}` : null,
      actionCount,
      recent: labels.slice(-TRACK_RECENT_ACTIONS),
    };
  });
}

/** « Ce message porte 2 questions ». */
export function splitBannerText(questions: AtelierTurnQuestions): string {
  const count = questions.questions.length;
  return `Ce message porte ${count} question${count > 1 ? 's' : ''}`;
}
