import { SubjectJourney } from '../../core/models/journey.models';

/** Qui a la main (F-176 / SF-176-10, décision D7). */
export type HandState = 'user' | 'awaiting' | 'working';

/** Ce que l'agent attend : l'autorisation d'une commande, la réponse à une question, un geste du parcours. */
export type HandTarget = 'ask' | 'question' | 'journey' | null;

export interface Hand {
  state: HandState;
  label: string;
  target: HandTarget;
}

/** Ce dont la règle se dérive — l'état déjà connu du terminal, jamais une supposition. */
export interface HandInputs {
  submitting: boolean;
  pendingConfirmation: unknown | null;
  pendingQuestion: unknown | null;
  journey: SubjectJourney | null;
}

/** Vrai si le parcours attend un geste de l'utilisateur (carte, diagnostic, plan, clôture). */
export function journeyAwaitsUser(journey: SubjectJourney | null): boolean {
  if (!journey) {
    return false;
  }
  if (journey.mode !== 'GUIDE') {
    return !!journey.guidedProposal;
  }
  if (!journey.phase || journey.phase === 'CLOS') {
    return false;
  }
  return !!journey.plan?.awaitingValidation || !!journey.diagnosis?.pending || !!journey.closeProposed;
}

/**
 * **À qui la main** (F-176 / SF-176-10, D7) : une seule règle, dérivée de l'état du terminal.
 * 1. l'agent attend un geste (autorisation, question, parcours) — même pendant un tour en pause ;
 * 2. sinon, un tour tourne : l'agent travaille ;
 * 3. sinon : à vous.
 */
export function handOf(inputs: HandInputs): Hand {
  if (inputs.pendingConfirmation) {
    return { state: 'awaiting', label: 'L’agent attend votre autorisation', target: 'ask' };
  }
  if (inputs.pendingQuestion) {
    return { state: 'awaiting', label: 'L’agent attend votre réponse', target: 'question' };
  }
  if (!inputs.submitting && journeyAwaitsUser(inputs.journey)) {
    return { state: 'awaiting', label: 'L’agent attend votre validation', target: 'journey' };
  }
  if (inputs.submitting) {
    return { state: 'working', label: 'L’agent travaille…', target: null };
  }
  return { state: 'user', label: 'À vous — écrivez ou cliquez une option', target: null };
}
