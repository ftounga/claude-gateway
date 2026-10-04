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
}

/** « Libre », « Guidé · Investigation ». */
export function journeyLabel(journey: SubjectJourney | null): string {
  if (!journey || journey.mode !== 'GUIDE') {
    return 'Libre';
  }
  return journey.phaseLabel ? `Guidé · ${journey.phaseLabel}` : 'Guidé';
}
