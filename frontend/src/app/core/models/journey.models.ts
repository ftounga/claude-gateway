/**
 * **Le parcours du sujet d'un terminal** (F-176) : Libre par défaut, ou Guidé —
 * Investigation → Plan → Exécution → Vérification → Clos.
 */
export type JourneyMode = 'LIBRE' | 'GUIDE';

export type JourneyPhase = 'INVESTIGATION' | 'PLAN' | 'EXECUTION' | 'VERIFICATION' | 'CLOS';

/** Les phases dans l'ordre, avec leur libellé. */
export const JOURNEY_PHASES: ReadonlyArray<{ phase: JourneyPhase; label: string }> = [
  { phase: 'INVESTIGATION', label: 'Investigation' },
  { phase: 'PLAN', label: 'Plan' },
  { phase: 'EXECUTION', label: 'Exécution' },
  { phase: 'VERIFICATION', label: 'Vérification' },
  { phase: 'CLOS', label: 'Clos' },
];

/** Le parcours tel que la gateway le rend (GET /workspaces/{id}/journey). */
export interface SubjectJourney {
  mode: JourneyMode;
  /** `null` tant que le sujet n'a jamais été guidé. */
  phase: JourneyPhase | null;
  phaseLabel: string | null;
  phaseChangedAt: string | null;
  /** La carte [Passer en guidé] [Rester libre] qui attend un geste (SF-176-02), ou `null`. */
  guidedProposal?: { reason: string | null; proposedAt: string } | null;
  /** L'utilisateur a choisi de rester libre sur ce sujet (SF-176-02). */
  guidedDeclined?: boolean;
  /** Le plan structuré du sujet guidé (SF-176-03), ou `null`. */
  plan?: JourneyPlan | null;
}

/** La classe de risque d'une étape. */
export type JourneyRisk = 'LECTURE' | 'NOTES' | 'REVERSIBLE' | 'EXTERNE';

/** Une étape du plan (SF-176-03). */
export interface JourneyPlanStep {
  title: string;
  risk: JourneyRisk | string;
  riskLabel: string;
  verify: string | null;
  rollback: string | null;
  /** La clé de l'attente (F-175) dont l'étape dépend. */
  waitsOn: string | null;
  /** L'état de cette attente : A_FAIRE, DEMANDE, FAIT, ANNULE — `null` si aucune ne porte la clé. */
  waitsOnStatus: string | null;
  status: 'A_FAIRE' | 'FAIT' | 'VERIFIE' | 'ECHEC' | string;
  evidence: string | null;
  /** Nouvelle ou modifiée depuis le plan validé (amendement). */
  changed: boolean;
}

/** Le plan structuré (SF-176-03). */
export interface JourneyPlan {
  version: number;
  validatedVersion: number | null;
  validatedAt: string | null;
  /** Cette version attend le clic de l'utilisateur. */
  awaitingValidation: boolean;
  /** C'est la modification d'un plan déjà validé. */
  amendment: boolean;
  /** Étapes qui attendent une attente encore ouverte. */
  waitingInputs: number;
  steps: JourneyPlanStep[];
}

/** « à faire », « fait », « vérifié », « échec ». */
export function stepStatusLabel(status: string): string {
  switch (status) {
    case 'FAIT': return 'fait';
    case 'VERIFIE': return 'vérifié';
    case 'ECHEC': return 'échec';
    default: return 'à faire';
  }
}

/** « demandé », « à faire », « reçu », « annulé » — l'attente dont dépend une étape. */
export function waitsOnLabel(status: string | null): string {
  switch (status) {
    case 'DEMANDE': return 'demandé';
    case 'A_FAIRE': return 'à demander';
    case 'FAIT': return 'reçu';
    case 'ANNULE': return 'annulé';
    default: return 'attente inconnue';
  }
}

/** « Libre », « Guidé · Investigation ». */
export function journeyLabel(journey: SubjectJourney | null): string {
  if (!journey || journey.mode !== 'GUIDE') {
    return 'Libre';
  }
  return journey.phaseLabel ? `Guidé · ${journey.phaseLabel}` : 'Guidé';
}
